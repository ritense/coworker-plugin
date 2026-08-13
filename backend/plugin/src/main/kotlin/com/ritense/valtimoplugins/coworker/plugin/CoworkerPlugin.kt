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
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.processdocument.helper.GetJsonSchemaDocumentHelper.getJsonSchemaDocumentId
import com.ritense.processlink.domain.ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END
import com.ritense.processlink.domain.ActivityTypeWithEventName.INTERMEDIATE_THROW_EVENT_START
import com.ritense.processlink.domain.ActivityTypeWithEventName.RECEIVE_TASK_END
import com.ritense.processlink.domain.ActivityTypeWithEventName.SEND_TASK_START
import com.ritense.valtimo.contract.document.CaseDocumentResolver
import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.CoworkerEventType
import com.ritense.valtimoplugins.coworker.service.CoworkerDocumentResolver
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService.Companion.VAR_CLOUD_EVENT_ID
import com.ritense.valtimoplugins.coworker.service.PromptTemplateResolver
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution

@Plugin(
    key = "coworker",
    title = "Coworker Plugin",
    description = "Sends and receives CoWorker chat events over RabbitMQ",
)
class CoworkerPlugin(
    private val restCoworkerChatClient: RestCoworkerChatClient,
    private val rabbitMqCoworkerChatClient: RabbitMqCoworkerChatClient,
    private val caseDocumentResolver: CaseDocumentResolver,
    private val promptTemplateResolver: PromptTemplateResolver,
    private val coworkerDocumentResolver: CoworkerDocumentResolver,
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
        val cloudEventId = rabbitMqCoworkerChatClient.publish(request, source, requestQueue)

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
    }
}
