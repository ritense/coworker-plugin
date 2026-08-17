# Coworker Plugin — technical reference

Implementation, operations, and configuration detail behind the plugin. For the
UI-facing guide (configuration fields, actions, process variables) see
[plugin.md](plugin.md).

## Transport

The plugin speaks the CoWorker contract over RabbitMQ:

- **RabbitMQ (asynchronous request/reply)** — `RabbitMqCoworkerChatClient` publishes a
  `nl.valtimo.coworker.chat-request` CloudEvent as raw JSON (`content-type:
  application/json`) to the request queue via a dedicated `RabbitTemplate`. The
  envelope is hand-rolled plain JSON matching the server's current wire format (NL GOV
  CloudEvents 1.1) — no CloudEvents SDK (that is a later version). Used by
  `publish-coworker`; the reply arrives on the plugin's own reply queue.

### Broker connection

Each plugin configuration carries its own broker connection (`rabbitMqHost`,
`rabbitMqPort`, `rabbitMqVirtualHost`, `rabbitMqUsername`, `rabbitMqPassword`,
`rabbitMqSslEnabled`). Every field is optional and falls back to the host app's
`spring.rabbitmq.*`, so a configuration that fills in only a username and password
keeps the application's host and port, and a configuration that fills in nothing
behaves exactly as before.

The host app is **not** required to configure a broker of its own. A configuration
that names its own broker is self-sufficient, which is what lets a CoWorker plugin be
set up entirely through the Valtimo web interface without touching the application's
`application.yml`. `CoworkerConnectionFactoryProvider` takes the application's
`ConnectionFactory` as an optional dependency (`ObjectProvider.getIfAvailable()`), so
an app without `spring.rabbitmq.*` — and therefore without a `ConnectionFactory` bean —
starts normally. Only a configuration that leaves every field empty needs the
application's connection, and it fails with an explanation naming the fields to fill
in when there is none, at the point of use rather than at startup.

`CoworkerConnectionFactoryProvider` resolves these to a `ConnectionFactory`: no
overrides means the application's own factory is reused, otherwise a
`CachingConnectionFactory` is created and cached per distinct set of settings, so
configurations pointing at the same broker with the same credentials share one
connection. `rabbitMqPassword` is stored `secret = true` (encrypted at rest,
never returned to the frontend) and is masked in logs.

> Spring's `ConnectionFactory` interface does not expose the password it was built
> with, so it cannot be inherited. A configuration that sets `rabbitMqUsername` must
> set `rabbitMqPassword` too.

#### amqp vs amqps

**TLS is never implied by the port.** In the RabbitMQ client,
`ConnectionFactory.isSSL()` is `getSocketFactory() instanceof SSLSocketFactory ||
sslContextFactory != null` — the transport depends solely on the socket factory, and
`portOrDefault(port, ssl)` runs the other way round: the TLS setting picks the
*default* port (5671 vs 5672), never the reverse. Setting `rabbitMqPort` to 5671 on its
own therefore opens a plaintext socket against a TLS listener, which stalls until the
connection timeout rather than failing with a TLS error.

The scheme itself only exists a layer up: Spring Boot's `RabbitProperties.Address`
parses an `amqps://` prefix into `secureConnection = true`, `spring.rabbitmq.ssl.enabled`
is the explicit switch, and `RabbitConnectionFactoryBeanConfigurer` turns either into
`setUseSSL(true)` plus the SSL bundle. Below that, a `ConnectionFactory` has no notion
of a URI scheme.

The provider therefore does two things:

- It builds each per-configuration factory from a `clone()` of the application's
  underlying `com.rabbitmq.client.ConnectionFactory`, so the app's TLS setup (and its
  timeouts and SASL config) is **inherited**. Without this, filling in only a username
  on a `spring.rabbitmq.ssl.enabled=true` deployment would silently drop to plaintext
  and send the credentials in the clear. The clone is shallow, which is what is wanted —
  the `SSLSocketFactory` is shared, not rebuilt. Where the app has no connection of its
  own there is nothing to inherit, and the configuration starts from the RabbitMQ
  client's defaults instead; `rabbitMqSslEnabled` then has to be set explicitly.
- `rabbitMqSslEnabled` forces the transport for a broker whose transport differs from
  the app's. It uses the JVM default `SSLContext`, so the platform trust store applies
  and certificates are actually validated, with `enableHostnameVerification()` on —
  deliberately not the no-argument `useSslProtocol()`, which trusts every certificate
  presented and is documented as development-only. When `rabbitMqSslEnabled` is set and
  `rabbitMqPort` is left empty, the port is reset to `USE_DEFAULT_PORT` so the client
  resolves 5671 or 5672 to match, instead of keeping the port inherited from the app.

### Reply listeners

The reply side is driven by the same configurations rather than a static
`@RabbitListener`, because neither the queue name nor the credentials are known until
a configuration has been saved. `CoworkerReplyListenerManager` keeps one
`SimpleMessageListenerContainer` per distinct (`replyQueue`, connection) pair found
across the stored CoWorker configurations, and declares each queue durably on its own
connection. It re-synchronises:

- on `ApplicationReadyEvent`;
- when a configuration is saved — via `CoworkerPlugin`'s `@PluginEvent(CREATE, UPDATE)`,
  which Valtimo also invokes for autodeployed configurations (unlike
  `PluginConfigurationCreatedEvent`, which autodeployment does not publish);
- when one is deleted — via `PluginConfigurationDeletedEvent`, which fires *after* the
  row is gone (the plugin's own DELETE event runs before it, and would still see the
  configuration being dropped);
- periodically (`valtimo.coworker.listener-refresh-interval`, 5 minutes by default) as
  a safety net for a rolled-back save or a configuration created on another node.

Setting `spring.rabbitmq.listener.simple.auto-startup: false` suppresses all CoWorker
reply listeners, which is how the integration-test harness runs without a broker.

## `caseId` resolution

For `publish-coworker` the request's `caseId` is derived from the process's document,
not configured: the plugin reads the document id (`getJsonSchemaDocumentId()`) and
resolves the owning case document id via Valtimo's `CaseDocumentResolver`
(`resolveCaseDocumentId(documentId)`), sent as its string form.

> `CaseDocumentResolver` is therefore a **required bean** in any context that loads
> the plugin (including integration-test contexts).

## Correlation model (async path)

Correlation is done by the process engine, without a side table:

- `publish-coworker` sets `coworkerCloudEventId = <request CloudEvent id>` **execution-local**.
- The reply carries that id back as `data.correlationId`.
- On a reply, the engine selects the waiting branch whose execution-local
  `coworkerCloudEventId` equals `data.correlationId`:
    - **Receive Task** → `createExecutionQuery().variableValueEquals(...)` + `signal(...)`.
    - **Intermediate Catch Event (Message)** → `createMessageCorrelation(...).localVariableEquals(...)`.

A correlated reply always resumes a waiting branch — the plugin never starts a new
process from a reply.

### Execution-local scope & parallelism

The correlation id and the delivered result variables are written **execution-local**
(`setVariableLocal` / `variableValueEquals` / `localVariableEquals`). This keeps
parallel-gateway branches and multi-instance loops isolated — each resumes with its
own reply instead of racing on one shared process-instance variable. It also avoids
`OptimisticLockingException` / lost updates under an "`async before`/`async after` on
every task" policy, where branches genuinely run on separate threads and transactions.

Caveat: an execution-local variable ends when its branch reaches the join. To keep a
value past the merge, use a BPMN output mapping on the step.

### Unmatched replies (retry)

A reply that arrives before its process reached the receive step is stored in
`coworker_failed_event` and retried on a schedule (`valtimo.coworker.retry-cron`,
hourly by default). The row is removed once handled (or when no longer processable).

### Hardening

The reply path (mirroring `coworker-client`) ignores foreign CloudEvents via a
known-`type` filter, tolerates a blank `type`, and deduplicates by CloudEvent id via
`coworker_processed` (an event is only marked processed once handled).

## Application configuration

The host app may provide a fallback broker connection (`spring.rabbitmq.*`, used for any
field a plugin configuration leaves empty). It is optional — a plugin configuration that
describes its broker in full needs none of it. The host app also provides:

| Property                       | Default                 | Description                                     |
|--------------------------------|-------------------------|-------------------------------------------------|
| `valtimo.coworker.retry-cron`  | `0 0 * * * *`           | Cron for retrying unmatched replies.            |
| `valtimo.coworker.listener-refresh-interval` | `PT5M`    | How often the reply listeners are reconciled against the stored plugin configurations. |
| `valtimo.coworker.max-document-size` | `10MB`            | Maximum size of a document sent along with a chat-request. The file is base64-encoded into the CloudEvent, so it is ~33% larger on the wire and must fit within the broker's message size limit. |

The reply queue name comes from the plugin configuration's `replyQueue`; the manager
declares it durably when it starts listening, and the sandbox app also declares it in
`backend/app/imports/plugin-rabbitmq/definitions.json` alongside `vcs.chat.in`.

> Before per-configuration connections existed, the consumed queue was set by
> `valtimo.coworker.reply-queue` and had to be kept in step with each configuration's
> `replyQueue`. That property is gone — the plugin configuration is now the only place
> the reply queue is named.

## Database

| Table                   | Purpose                                                      |
|-------------------------|--------------------------------------------------------------|
| `coworker_processed`    | Dedup of handled reply CloudEvent ids.                       |
| `coworker_failed_event` | Valid replies awaiting retry (raw payload, attempt counter). |

Schemas are managed via Liquibase (`config/liquibase/coworker-master.xml`).
