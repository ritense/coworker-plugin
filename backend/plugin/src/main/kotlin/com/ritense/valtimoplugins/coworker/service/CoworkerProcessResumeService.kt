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

package com.ritense.valtimoplugins.coworker.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.annotation.RunWithoutAuthorization
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimoplugins.coworker.domain.ReceiveCoworkerProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition

/**
 * Resumes BPMN activities linked to the `receive-coworker` action for an
 * incoming CoWorker reply.
 *
 * Correlation is done by the **process engine** using the `coworkerCloudEventId`
 * process variable that `publish-coworker` set to the request's CloudEvent id.
 * The reply's `data.correlationId` carries that same id back, so the waiting
 * branch is selected by variable equality — concurrency-correct without a side
 * table. The correlation id and delivered result variables are execution-**local**,
 * so parallel branches (parallel gateway / multi-instance) each resume with their
 * own reply instead of racing on one shared process-instance variable.
 *
 * [resume] returns `true` when at least one activity was resumed; `false` means
 * nothing matched yet (e.g. the response beat the process to its receive step) so
 * the caller can retry later.
 */
open class CoworkerProcessResumeService(
    private val pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
    private val runtimeService: RuntimeService,
    private val repositoryService: RepositoryService,
    private val objectMapper: ObjectMapper,
) {
    @RunWithoutAuthorization
    open fun resume(
        correlationId: String?,
        eventType: String,
        variables: Map<String, Any>,
    ): Boolean {
        val processLinks = pluginProcessLinkRepository.findByPluginActionDefinitionKey(ACTION_KEY)
        if (processLinks.isEmpty()) {
            logger.debug { "No receive-coworker process links found" }
            return false
        }

        var handled = false
        processLinks
            .filter { matchesFilter(it, eventType) }
            .forEach { processLink ->
                val resumed =
                    when (processLink.activityType) {
                        ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END ->
                            correlateCatchEvent(processLink, correlationId, variables)
                        ActivityTypeWithEventName.RECEIVE_TASK_END ->
                            signalReceiveTask(processLink, correlationId, variables)
                        else -> {
                            logger.warn {
                                "Unsupported activity type '${processLink.activityType}' for coworker process link"
                            }
                            false
                        }
                    }
                handled = handled || resumed
            }
        return handled
    }

    private fun matchesFilter(
        processLink: PluginProcessLink,
        eventType: String,
    ): Boolean {
        val properties = processLink.actionProperties ?: return true
        val filter = objectMapper.treeToValue(properties, ReceiveCoworkerProperties::class.java)
        return filter.eventType.isNullOrBlank() || filter.eventType == eventType
    }

    /**
     * Resumes the receive task in the one branch whose execution-local
     * [VAR_CLOUD_EVENT_ID] variable equals the reply's correlation id. Uses
     * execution-scoped matching (`variableValueEquals`) and delivers the result
     * variables execution-local so parallel branches stay isolated.
     */
    private fun signalReceiveTask(
        processLink: PluginProcessLink,
        correlationId: String?,
        variables: Map<String, Any>,
    ): Boolean {
        if (correlationId.isNullOrBlank()) return false
        val executions =
            runtimeService
                .createExecutionQuery()
                .processDefinitionId(processLink.processDefinitionId)
                .activityId(processLink.activityId)
                .variableValueEquals(VAR_CLOUD_EVENT_ID, correlationId)
                .list()

        if (executions.isEmpty()) {
            logger.debug {
                "No receive task waiting for correlationId '$correlationId' at activity '${processLink.activityId}'"
            }
            return false
        }
        executions.forEach { execution ->
            logger.info {
                "Signaling execution '${execution.id}' in process instance '${execution.processInstanceId}' for correlationId '$correlationId'"
            }
            runtimeService.setVariablesLocal(execution.id, variables)
            runtimeService.signal(execution.id)
        }
        return true
    }

    /**
     * Delivers the reply to the message catch event in the branch whose
     * execution-local [VAR_CLOUD_EVENT_ID] variable equals the reply's correlation
     * id. Matches and delivers execution-local so parallel branches stay isolated.
     */
    private fun correlateCatchEvent(
        processLink: PluginProcessLink,
        correlationId: String?,
        variables: Map<String, Any>,
    ): Boolean {
        if (correlationId.isNullOrBlank()) return false
        val messageName = getMessageName(processLink)
        val results =
            runtimeService
                .createMessageCorrelation(messageName)
                .localVariableEquals(VAR_CLOUD_EVENT_ID, correlationId)
                .setVariablesLocal(variables)
                .correlateAllWithResult()

        if (results.isEmpty()) {
            logger.debug {
                "No process instance waiting for message '$messageName' with correlationId '$correlationId'"
            }
            return false
        }
        logger.info {
            "Correlated message '$messageName' to ${results.size} instance(s) for correlationId '$correlationId'"
        }
        return true
    }

    private fun getMessageName(processLink: PluginProcessLink): String {
        val model = repositoryService.getBpmnModelInstance(processLink.processDefinitionId)
        val element = model.getModelElementById<CatchEvent>(processLink.activityId)
        val messageEventDefinition =
            element.eventDefinitions
                .filterIsInstance<MessageEventDefinition>()
                .firstOrNull()
                ?: throw IllegalStateException(
                    "No message event definition found on element '${processLink.activityId}' " +
                        "in process definition '${processLink.processDefinitionId}'",
                )
        return messageEventDefinition.message.name
    }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val ACTION_KEY = "receive-coworker"

        /** Process variable holding the request's CloudEvent id (set by publish-coworker). */
        const val VAR_CLOUD_EVENT_ID = "coworkerCloudEventId"
    }
}
