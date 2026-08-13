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

package com.ritense.valtimoplugins.coworker

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService.Companion.VAR_CLOUD_EVENT_ID
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseVariables
import com.ritense.valtimoplugins.coworker.service.CoworkerResultMapper
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.ExecutionQuery
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.engine.runtime.ProcessInstanceQuery
import java.util.UUID

class CoworkerProcessResumeServiceTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val processLinkRepository = mock<ValtimoPluginProcessLinkRepository>()
    private val runtimeService = mock<RuntimeService>()
    private val repositoryService = mock<RepositoryService>()
    private val valueResolverService = mock<ValueResolverService>()

    private val service =
        CoworkerProcessResumeService(
            processLinkRepository,
            runtimeService,
            repositoryService,
            CoworkerResultMapper(objectMapper),
            valueResolverService,
            objectMapper,
        )

    private val documentId = UUID.fromString("d3e921a6-cb06-4afd-8f06-d3a82ca9ff2d")
    private val correlationId = "cloud-event-1"
    private val answer = """{"netto": 100, "totaal": 121}"""

    /** A receive-task process link with the given `receive-coworker` action properties. */
    private fun processLink(actionProperties: String?): PluginProcessLink {
        val properties = actionProperties?.let { objectMapper.readTree(it) as ObjectNode }
        return mock<PluginProcessLink>().also {
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn("receiveCoworker")
            whenever(it.processDefinitionId).thenReturn("process-definition-1")
            whenever(it.actionProperties).thenReturn(properties)
        }
    }

    /** Stubs one branch waiting at the activity with a case document behind it. */
    private fun stubWaitingExecution() {
        val execution =
            mock<Execution>().also {
                whenever(it.id).thenReturn("exec-1")
                whenever(it.processInstanceId).thenReturn("proc-1")
            }
        val executionQuery = mock<ExecutionQuery>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(executionQuery.list()).thenReturn(listOf(execution))
        whenever(runtimeService.createExecutionQuery()).thenReturn(executionQuery)

        val processInstance =
            mock<ProcessInstance>().also { whenever(it.businessKey).thenReturn(documentId.toString()) }
        val processInstanceQuery = mock<ProcessInstanceQuery>(defaultAnswer = Answers.RETURNS_SELF)
        whenever(processInstanceQuery.singleResult()).thenReturn(processInstance)
        whenever(runtimeService.createProcessInstanceQuery()).thenReturn(processInstanceQuery)
    }

    private fun replyVariables(content: String? = answer): Map<String, Any> =
        buildMap {
            put("coworkerSuccess", true)
            content?.let { put(CoworkerResponseVariables.VAR_CONTENT, it) }
        }

    @Test
    fun `writes mapped document values before resuming the process`() {
        val link = processLink("""{"resultMappings":[{"source":"/netto","target":"doc:/factuur/netto"}]}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))
        stubWaitingExecution()

        val handled = service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables())

        assertThat(handled).isTrue()
        // The document must be written before the branch continues, otherwise the next
        // step reads a document that does not have the answer in it yet.
        inOrder(valueResolverService, runtimeService) {
            verify(valueResolverService).handleValues(eq(documentId), any())
            verify(runtimeService).signal("exec-1")
        }

        val valuesCaptor = argumentCaptor<Map<String, Any>>()
        verify(valueResolverService).handleValues(eq(documentId), valuesCaptor.capture())
        assertThat(valuesCaptor.firstValue).containsEntry("doc:/factuur/netto", 100)
    }

    @Test
    fun `delivers mapped process variables alongside the standard reply variables`() {
        val link = processLink("""{"resultMappings":[{"source":"/totaal","target":"pv:totaalbedrag"}]}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))
        stubWaitingExecution()

        service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables())

        val variablesCaptor = argumentCaptor<Map<String, Any>>()
        verify(runtimeService).setVariablesLocal(eq("exec-1"), variablesCaptor.capture())
        assertThat(variablesCaptor.firstValue)
            .containsEntry("totaalbedrag", 121)
            .containsEntry("coworkerSuccess", true)
        verify(valueResolverService, never()).handleValues(any<UUID>(), any())
    }

    @Test
    fun `resumes with a mapping error when the answer is not JSON`() {
        val link = processLink("""{"resultMappings":[{"source":"/netto","target":"pv:netto"}]}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))
        stubWaitingExecution()

        val handled = service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables("Geen JSON"))

        // The process continues; it can branch on coworkerMappingError.
        assertThat(handled).isTrue()
        val variablesCaptor = argumentCaptor<Map<String, Any>>()
        verify(runtimeService).setVariablesLocal(eq("exec-1"), variablesCaptor.capture())
        assertThat(variablesCaptor.firstValue).containsKey(CoworkerResponseVariables.VAR_MAPPING_ERROR)
        assertThat(variablesCaptor.firstValue).doesNotContainKey("netto")
    }

    @Test
    fun `resumes without touching the document when no mapping is configured`() {
        val link = processLink("""{"eventType":"nl.valtimo.coworker.chat-response"}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))
        stubWaitingExecution()

        val handled = service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables())

        assertThat(handled).isTrue()
        verify(valueResolverService, never()).handleValues(any<UUID>(), any())
        verify(runtimeService).signal("exec-1")
    }

    @Test
    fun `skips a process link whose event type filter does not match`() {
        val link = processLink("""{"eventType":"nl.valtimo.coworker.chat-error"}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))

        val handled = service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables())

        assertThat(handled).isFalse()
        verify(runtimeService, never()).signal(any())
    }

    @Test
    fun `still resumes when the document cannot be written`() {
        val link = processLink("""{"resultMappings":[{"source":"/netto","target":"doc:/netto"}]}""")
        whenever(processLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(listOf(link))
        stubWaitingExecution()
        whenever(valueResolverService.handleValues(any<UUID>(), any())).thenThrow(RuntimeException("boom"))

        val handled = service.resume(correlationId, "nl.valtimo.coworker.chat-response", replyVariables())

        assertThat(handled).isTrue()
        verify(runtimeService).signal("exec-1")
    }

    @Test
    fun `keeps VAR_CLOUD_EVENT_ID as the correlation variable`() {
        // Guards the contract with publish-coworker: the same variable name is written
        // there and matched here.
        assertThat(VAR_CLOUD_EVENT_ID).isEqualTo("coworkerCloudEventId")
    }
}
