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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.document.CaseDocumentResolver
import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.ChatResponseData
import com.ritense.valtimoplugins.coworker.plugin.CoworkerPlugin
import com.ritense.valtimoplugins.coworker.service.CoworkerDocumentResolver
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService.Companion.VAR_CLOUD_EVENT_ID
import com.ritense.valtimoplugins.coworker.service.PromptTemplateResolver
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.delegate.DelegateExecution
import java.util.UUID

class CoworkerPluginTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val restChatClient = mock<RestCoworkerChatClient>()
    private val rabbitChatClient = mock<RabbitMqCoworkerChatClient>()

    // The process's document id (from the execution's business key) and the case
    // document id the resolver maps it to (which becomes the CoWorker caseId).
    private val documentId = UUID.fromString("d3e921a6-cb06-4afd-8f06-d3a82ca9ff2d")
    private val caseDocumentId = UUID.fromString("a1b2c3d4-1111-2222-3333-444455556666")
    private val caseDocumentResolver =
        mock<CaseDocumentResolver>().also {
            whenever(it.resolveCaseDocumentId(any())).thenReturn(caseDocumentId)
        }

    // A real PromptTemplateResolver over a mocked ValueResolverService, so the
    // plugin's prompt-templating step is exercised end to end.
    private val valueResolverService = mock<ValueResolverService>()
    private val promptTemplateResolver = PromptTemplateResolver(valueResolverService, objectMapper)

    private val documentStorageService = mock<TemporaryResourceStorageService>()
    private val coworkerDocumentResolver = CoworkerDocumentResolver(documentStorageService, 1024)

    private val plugin =
        CoworkerPlugin(
            restChatClient,
            rabbitChatClient,
            caseDocumentResolver,
            promptTemplateResolver,
            coworkerDocumentResolver,
            objectMapper,
        ).apply {
            source = "urn:nld:oin:00000000000000000001:systeem:coworker-plugin"
            requestQueue = "vcs.chat.in"
            replyQueue = "coworker-plugin.reply"
        }

    private fun execution(): DelegateExecution =
        mock<DelegateExecution>().also {
            whenever(it.id).thenReturn("exec-1")
            whenever(it.processInstanceId).thenReturn("proc-1")
            // getJsonSchemaDocumentId() reads the execution's business key.
            whenever(it.businessKey).thenReturn(documentId.toString())
        }

    @Test
    fun `sends a chat-request and stores the correlation variable`() {
        whenever(rabbitChatClient.publish(any(), any(), any())).thenReturn("cloud-event-1")
        val execution = execution()

        plugin.publishCoworker(
            execution = execution,
            coworkerId = "cw-1",
            userPrompt = "Summarize the case",
            expertiseId = null,
            input = null,
            documentResourceId = null,
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(rabbitChatClient).publish(requestCaptor.capture(), eq(plugin.source), eq("vcs.chat.in"))
        val request = requestCaptor.firstValue
        assertThat(request.coworkerId).isEqualTo("cw-1")
        // caseId is the resolved case document id (from the execution's document).
        assertThat(request.caseId).isEqualTo(caseDocumentId.toString())
        assertThat(request.userPrompt).isEqualTo("Summarize the case")
        assertThat(request.replyTo).isEqualTo("coworker-plugin.reply")

        verify(execution).setVariableLocal(VAR_CLOUD_EVENT_ID, "cloud-event-1")
    }

    @Test
    fun `parses a JSON input string into a json node`() {
        whenever(rabbitChatClient.publish(any(), any(), any())).thenReturn("cloud-event-2")

        plugin.publishCoworker(
            execution = execution(),
            coworkerId = "cw-2",
            userPrompt = null,
            expertiseId = "expertise-2",
            input = """{"key":"value"}""",
            documentResourceId = null,
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(rabbitChatClient).publish(requestCaptor.capture(), any(), any())
        val input = requestCaptor.firstValue.input
        assertThat(input).isNotNull
        assertThat(input!!["key"].asText()).isEqualTo("value")
    }

    /*
     * DISABLED in v1 alongside the chat-coworker (REST) action — see CoworkerPlugin.kt.
     * Re-enable together with the action once the CoWorker server's chat endpoint is fixed.
     *
    @Test
    fun `chat-coworker calls REST and writes the reply variables inline`() {
        plugin.coworkerUrl = "https://coworker.example.nl"
        plugin.coworkerUsername = "user"
        plugin.coworkerPassword = "secret"
        whenever(restChatClient.chat(any(), any(), any(), any()))
            .thenReturn(ChatResponseData(content = "the answer", success = true, messageId = "m1"))
        val execution = execution()

        plugin.chatCoworker(
            execution = execution,
            coworkerId = "cw-1",
            userPrompt = "Summarize the case",
            expertiseId = null,
            input = null,
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(
            restChatClient,
        ).chat(requestCaptor.capture(), eq("https://coworker.example.nl"), eq("user"), eq("secret"))
        assertThat(requestCaptor.firstValue.coworkerId).isEqualTo("cw-1")
        // The service task never touches RabbitMQ.
        verify(rabbitChatClient, never()).publish(any(), any(), any())

        val varsCaptor = argumentCaptor<Map<String, Any>>()
        verify(execution).setVariablesLocal(varsCaptor.capture())
        val vars = varsCaptor.firstValue
        assertThat(vars["coworkerContent"]).isEqualTo("the answer")
        assertThat(vars["coworkerSuccess"]).isEqualTo(true)
        assertThat(vars["coworkerType"]).isEqualTo("nl.valtimo.coworker.chat-response")
        assertThat(vars["coworkerEventId"]).isEqualTo("m1")
    }

    @Test
    fun `chat-coworker maps a chat-error reply`() {
        plugin.coworkerUrl = "https://coworker.example.nl"
        whenever(restChatClient.chat(any(), any(), anyOrNull(), anyOrNull()))
            .thenReturn(ChatResponseData(content = "boom", success = false, error = "bad"))
        val execution = execution()

        plugin.chatCoworker(
            execution = execution,
            coworkerId = "cw-1",
            userPrompt = "Summarize the case",
            expertiseId = null,
            input = null,
        )

        val varsCaptor = argumentCaptor<Map<String, Any>>()
        verify(execution).setVariablesLocal(varsCaptor.capture())
        val vars = varsCaptor.firstValue
        assertThat(vars["coworkerSuccess"]).isEqualTo(false)
        assertThat(vars["coworkerType"]).isEqualTo("nl.valtimo.coworker.chat-error")
        assertThat(vars["coworkerError"]).isEqualTo("bad")
    }
    */

    @Test
    fun `fills prompt placeholders with case data before publishing`() {
        whenever(rabbitChatClient.publish(any(), any(), any())).thenReturn("cloud-event-3")
        whenever(valueResolverService.supportsValue(any())).thenReturn(true)
        whenever(valueResolverService.resolveValues(eq("proc-1"), any<DelegateExecution>(), any()))
            .thenReturn(mapOf("doc:/vraag" to "Mag ik een vergunning?"))

        plugin.publishCoworker(
            execution = execution(),
            coworkerId = "cw-3",
            userPrompt = "Beoordeel {{doc:/vraag}} op spoed",
            expertiseId = null,
            input = null,
            documentResourceId = null,
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(rabbitChatClient).publish(requestCaptor.capture(), any(), any())
        assertThat(requestCaptor.firstValue.userPrompt)
            .isEqualTo("Beoordeel Mag ik een vergunning? op spoed")
    }

    @Test
    fun `attaches the document behind a resource id`() {
        whenever(rabbitChatClient.publish(any(), any(), any())).thenReturn("cloud-event-5")
        whenever(documentStorageService.getResourceContentAsInputStream("res-1"))
            .thenReturn("factuur".byteInputStream())
        whenever(documentStorageService.getResourceMetadata("res-1"))
            .thenReturn(
                mapOf(
                    MetadataType.FILE_NAME.key to "factuur.pdf",
                    MetadataType.CONTENT_TYPE.key to "application/pdf",
                ),
            )

        plugin.publishCoworker(
            execution = execution(),
            coworkerId = "cw-5",
            userPrompt = "Lees deze factuur",
            expertiseId = null,
            input = null,
            documentResourceId = "res-1",
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(rabbitChatClient).publish(requestCaptor.capture(), any(), any())
        val documents = requestCaptor.firstValue.documents
        assertThat(documents).hasSize(1)
        assertThat(documents!!.first().fileName).isEqualTo("factuur.pdf")
        assertThat(documents.first().contentType).isEqualTo("application/pdf")
    }

    @Test
    fun `sends no documents when no resource id is configured`() {
        whenever(rabbitChatClient.publish(any(), any(), any())).thenReturn("cloud-event-6")

        plugin.publishCoworker(
            execution = execution(),
            coworkerId = "cw-6",
            userPrompt = "Vat samen",
            expertiseId = null,
            input = null,
            documentResourceId = null,
        )

        val requestCaptor = argumentCaptor<ChatRequestData>()
        verify(rabbitChatClient).publish(requestCaptor.capture(), any(), any())
        assertThat(requestCaptor.firstValue.documents).isNull()
    }

    @Test
    fun `does not publish when a prompt placeholder cannot be resolved`() {
        whenever(valueResolverService.supportsValue(any())).thenReturn(true)
        whenever(valueResolverService.resolveValues(eq("proc-1"), any<DelegateExecution>(), any()))
            .thenReturn(emptyMap())

        val ex =
            runCatching {
                plugin.publishCoworker(
                    execution = execution(),
                    coworkerId = "cw-4",
                    userPrompt = "Beoordeel {{doc:/onbekend}}",
                    expertiseId = null,
                    input = null,
                    documentResourceId = null,
                )
            }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        verify(rabbitChatClient, never()).publish(any(), any(), any())
    }

    @Test
    fun `rejects a request without prompt or expertise input`() {
        val ex =
            runCatching {
                plugin.publishCoworker(
                    execution = execution(),
                    coworkerId = "cw-1",
                    userPrompt = null,
                    expertiseId = null,
                    input = null,
                    documentResourceId = null,
                )
            }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects a request without a coworkerId`() {
        val ex =
            runCatching {
                plugin.publishCoworker(
                    execution = execution(),
                    coworkerId = null,
                    userPrompt = "Summarize the case",
                    expertiseId = null,
                    input = null,
                    documentResourceId = null,
                )
            }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
    }
}
