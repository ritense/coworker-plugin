/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.coworker.plugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.plugin.annotation.PluginEvent
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.plugin.domain.EventType
import com.ritense.processdocument.helper.GetJsonSchemaDocumentHelper.getJsonSchemaDocumentId
import com.ritense.processlink.domain.ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END
import com.ritense.processlink.domain.ActivityTypeWithEventName.INTERMEDIATE_THROW_EVENT_START
import com.ritense.processlink.domain.ActivityTypeWithEventName.RECEIVE_TASK_END
import com.ritense.processlink.domain.ActivityTypeWithEventName.SEND_TASK_START
import com.ritense.valtimo.contract.document.CaseDocumentResolver
import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.CoworkerEventType
import com.ritense.valtimoplugins.coworker.domain.CoworkerRabbitMqProperties
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListenerManager
import com.ritense.valtimoplugins.coworker.service.CoworkerDocumentResolver
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService.Companion.VAR_CLOUD_EVENT_ID
import com.ritense.valtimoplugins.coworker.service.PromptTemplateResolver
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution

@Plugin(
    key = CoworkerPlugin.PLUGIN_KEY,
    title = "Coworker Plugin",
    description = "Sends and receives CoWorker chat events over RabbitMQ",
)
class CoworkerPlugin(
    private val restCoworkerChatClient: RestCoworkerChatClient,
    private val rabbitMqCoworkerChatClient: RabbitMqCoworkerChatClient,
    private val caseDocumentResolver: CaseDocumentResolver,
    private val promptTemplateResolver: PromptTemplateResolver,
    private val coworkerDocumentResolver: CoworkerDocumentResolver,
    private val replyListenerManager: CoworkerReplyListenerManager,
    private val objectMapper: ObjectMapper,
) {
    @PluginProperty(key = "source", secret = false, required = true)
    lateinit var source: String

    @PluginProperty(key = "requestQueue", secret = false, required = true)
    lateinit var requestQueue: String

    @PluginProperty(key = "replyQueue", secret = false, required = true)
    lateinit var replyQueue: String

    @PluginProperty(key = "coworkerUrl", secret = false, required = false)
    var coworkerUrl: String? = null

    @PluginProperty(key = "coworkerUsername", secret = false, required = false)
    var coworkerUsername: String? = null

    @PluginProperty(key = "coworkerPassword", secret = true, required = false)
    var coworkerPassword: String? = null

    /*
     * Broker connection for both the request queue and the reply queue. All optional:
     * an empty field falls back to the host app's `spring.rabbitmq.*`, so an existing
     * configuration keeps working untouched and a broker that only needs credentials
     * can be given just those.
     */

    @PluginProperty(key = "rabbitMqHost", secret = false, required = false)
    var rabbitMqHost: String? = null

    @PluginProperty(key = "rabbitMqPort", secret = false, required = false)
    var rabbitMqPort: Int? = null

    @PluginProperty(key = "rabbitMqVirtualHost", secret = false, required = false)
    var rabbitMqVirtualHost: String? = null

    @PluginProperty(key = "rabbitMqUsername", secret = false, required = false)
    var rabbitMqUsername: String? = null

    @PluginProperty(key = "rabbitMqPassword", secret = true, required = false)
    var rabbitMqPassword: String? = null

    /**
     * Forces TLS (amqps) on or off for this configuration's broker; leave empty to
     * inherit the host app's setting. Setting [rabbitMqPort] to 5671 does *not* enable
     * TLS on its own — the RabbitMQ client decides on the socket factory alone.
     */
    @PluginProperty(key = "rabbitMqSslEnabled", secret = false, required = false)
    var rabbitMqSslEnabled: Boolean? = null

    /** The broker this configuration publishes to and consumes its replies from. */
    val rabbitMqProperties: CoworkerRabbitMqProperties
        get() =
            CoworkerRabbitMqProperties.of(
                host = rabbitMqHost,
                port = rabbitMqPort,
                virtualHost = rabbitMqVirtualHost,
                username = rabbitMqUsername,
                password = rabbitMqPassword,
                sslEnabled = rabbitMqSslEnabled,
            )

    /**
     * Picks up a new or changed reply queue / broker connection without a restart.
     * Runs on the admin-UI save path and on autodeployment alike, because Valtimo
     * invokes `@PluginEvent` methods from both. Deletion is handled by the manager's
     * own listener on `PluginConfigurationDeletedEvent`.
     *
     * Must never throw. Valtimo runs plugin events as part of the save transaction and
     * treats a failure as "this configuration is invalid": `createPluginConfiguration`
     * and autodeployment *delete* the configuration again, and `updatePluginConfiguration`
     * never reaches its `save()`. An unreachable broker or a wrong password would
     * otherwise throw away the very configuration being written to fix it. Failing to
     * attach a listener is reported here and retried by the manager's periodic reconcile.
     */
    @PluginEvent(invokedOn = [EventType.CREATE, EventType.UPDATE])
    fun onConfigurationSaved() {
        try {
            replyListenerManager.synchronize()
        } catch (e: Exception) {
            logger.error(e) {
                "Could not (re)start the CoWorker reply listeners after saving a configuration. " +
                    "The configuration itself is saved; the listeners will be retried automatically."
            }
        }
    }

    @PluginAction(
        key = "publish-coworker",
        title = "Ask CoWorker",
        description = "Sends a chat-request to the CoWorker server (RabbitMQ)",
        activityTypes = [SEND_TASK_START, INTERMEDIATE_THROW_EVENT_START],
    )
    fun publishCoworker(
        execution: DelegateExecution,
        @PluginActionProperty coworkerId: String?,
        @PluginActionProperty userPrompt: String?,
        @PluginActionProperty expertiseId: String?,
        @PluginActionProperty input: String?,
        @PluginActionProperty documentResourceId: String?,
    ) {
        val request = buildRequest(execution, coworkerId, userPrompt, expertiseId, input, documentResourceId)

        logger.debug { "Publishing chat-request for coworkerId '${request.coworkerId}', caseId '${request.caseId}'" }
        val cloudEventId = rabbitMqCoworkerChatClient.publish(request, source, requestQueue, rabbitMqProperties)

        // Expose the request's CloudEvent id so the reply can be correlated by the
        // engine: the matching chat-response carries it back as data.correlationId
        // (see CoworkerProcessResumeService). Written execution-LOCAL so parallel
        // branches (parallel gateway / multi-instance) each keep their own
        // correlation id instead of clobbering one shared process-instance variable.
        execution.setVariableLocal(VAR_CLOUD_EVENT_ID, cloudEventId)
        logger.info {
            "Published chat-request '$cloudEventId' from execution '${execution.id}' in process instance '${execution.processInstanceId}'"
        }
    }

    /*
     * DISABLED in v1: the synchronous REST chat-coworker action is turned off until
     * the CoWorker server's chat endpoint is fixed. `POST /api/v1/chat` currently
     * 500s with a LazyInitializationException on `Coworker.guardrailConfig`
     * (server-side, cannot be changed from this plugin in v1). Use publish-coworker
     * (RabbitMQ) + receive-coworker instead. Re-enable this action once the server
     * bug is resolved.
     *
    @PluginAction(
        key = "chat-coworker",
        title = "Chat with CoWorker",
        description = "Sends a chat-request over REST (RabbitMQ fallback) and stores the reply in process variables",
        activityTypes = [SERVICE_TASK_START],
    )
    fun chatCoworker(
        execution: DelegateExecution,
        @PluginActionProperty coworkerId: String?,
        @PluginActionProperty userPrompt: String?,
        @PluginActionProperty expertiseId: String?,
        @PluginActionProperty input: String?,
    ) {
        val request = buildRequest(execution, coworkerId, userPrompt, expertiseId, input)

        // The chat-coworker service task is synchronous REST only — no RabbitMQ and
        // no async reply. The answer comes back in the HTTP response and is written
        // straight to the coworker* process variables.
        val response = restCoworkerChatClient.chat(request, coworkerUrl, coworkerUsername, coworkerPassword)

        // Execution-local so parallel chat branches keep their own coworker* results.
        execution.setVariablesLocal(CoworkerResponseVariables.from(response))
        logger.info {
            "CoWorker REST chat completed (success=${response.success}) for execution '${execution.id}'"
        }
    }
     */

    @PluginAction(
        key = "receive-coworker",
        title = "Await CoWorker reply",
        description = "Receives a CoWorker chat-response/chat-error (RabbitMQ)",
        activityTypes = [RECEIVE_TASK_END, INTERMEDIATE_CATCH_EVENT_END],
    )
    fun receiveCoworker(
        @PluginActionProperty eventType: String?,
    ) {
        if (!eventType.isNullOrBlank()) {
            val allowed = CoworkerEventType.entries.joinToString { it.type }
            require(CoworkerEventType.isKnown(eventType)) {
                "Unknown CoWorker event type '$eventType'. Allowed: $allowed"
            }
        }
        logger.debug { "Receive coworker action registered for type filter: $eventType" }
    }

    /** Builds and validates the [ChatRequestData] shared by publish-coworker and chat-coworker. */
    private fun buildRequest(
        execution: DelegateExecution,
        coworkerId: String?,
        userPrompt: String?,
        expertiseId: String?,
        input: String?,
        documentResourceId: String? = null,
    ): ChatRequestData {
        require(source.startsWith("urn:")) { "CoWorker 'source' must be a urn: (was '$source')" }
        require(!coworkerId.isNullOrBlank()) { "'coworkerId' is required on the coworker action" }

        // The CoWorker caseId is the owning case document of the process's document.
        val documentId = execution.getJsonSchemaDocumentId()
        val caseDocumentId = caseDocumentResolver.resolveCaseDocumentId(documentId)

        // `{{pv:...}}` / `{{doc:...}}` placeholders are filled with case data before
        // the prompt leaves Valtimo; a prompt without placeholders passes through.
        val resolvedPrompt = promptTemplateResolver.resolve(userPrompt, execution)

        val request =
            ChatRequestData(
                coworkerId = coworkerId,
                caseId = caseDocumentId.toString(),
                userPrompt = resolvedPrompt?.takeIf { it.isNotBlank() },
                expertiseId = expertiseId?.takeIf { it.isNotBlank() },
                input = input?.takeIf { it.isNotBlank() }?.let { parseInput(it) },
                replyTo = replyQueue,
                // Optional: the file behind a Valtimo resource id, base64 in the event.
                documents = coworkerDocumentResolver.resolve(documentResourceId),
            )
        require(request.isValid()) {
            "chat-request requires either a 'userPrompt' or an 'expertiseId' + 'input'"
        }
        return request
    }

    /** Parses the `input` action property as JSON; falls back to a text value if it is not valid JSON. */
    private fun parseInput(raw: String): JsonNode =
        try {
            objectMapper.readTree(raw)
        } catch (_: Exception) {
            TextNode.valueOf(raw)
        }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** The plugin definition key, also used to look configurations up by key. */
        const val PLUGIN_KEY = "coworker"
    }
}
