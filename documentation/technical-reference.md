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

The host app provides the broker connection (`spring.rabbitmq.*`) and:

| Property                       | Default                 | Description                                     |
|--------------------------------|-------------------------|-------------------------------------------------|
| `valtimo.coworker.reply-queue` | `coworker-plugin.reply` | Reply queue name (declared durable on startup). |
| `valtimo.coworker.retry-cron`  | `0 0 * * * *`           | Cron for retrying unmatched replies.            |
| `valtimo.coworker.max-document-size` | `10MB`            | Maximum size of a document sent along with a chat-request. The file is base64-encoded into the CloudEvent, so it is ~33% larger on the wire and must fit within the broker's message size limit. |

The reply queue must exist on the broker (the plugin declares it as a durable `Queue`
bean; the sandbox app also declares it in
`backend/app/imports/plugin-rabbitmq/definitions.json` alongside `vcs.chat.in`).

## Database

| Table                   | Purpose                                                      |
|-------------------------|--------------------------------------------------------------|
| `coworker_processed`    | Dedup of handled reply CloudEvent ids.                       |
| `coworker_failed_event` | Valid replies awaiting retry (raw payload, attempt counter). |

Schemas are managed via Liquibase (`config/liquibase/coworker-master.xml`).
