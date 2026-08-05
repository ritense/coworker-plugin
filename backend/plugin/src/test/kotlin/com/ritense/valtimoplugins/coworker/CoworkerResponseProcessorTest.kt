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
import com.ritense.valtimoplugins.coworker.domain.ProcessedCoworker
import com.ritense.valtimoplugins.coworker.repository.ProcessedCoworkerRepository
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CoworkerResponseProcessorTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val processedRepository = mock<ProcessedCoworkerRepository>()
    private val resumeService = mock<CoworkerProcessResumeService>()

    private val processor = CoworkerResponseProcessor(objectMapper, processedRepository, resumeService)

    private val correlationId = "11111111-1111-1111-1111-111111111111"

    @Test
    fun `resumes by correlation id and maps content on a chat-response`() {
        whenever(processedRepository.existsById(any())).thenReturn(false)
        whenever(resumeService.resume(eq(correlationId), any(), any())).thenReturn(true)

        val outcome =
            processor.process(
                """
                {
                  "id":"event-1",
                  "type":"nl.valtimo.coworker.chat-response",
                  "source":"urn:test",
                  "data":{"correlationId":"$correlationId","success":true,"content":"Answer"}
                }
                """.trimIndent(),
            )

        assertThat(outcome.status).isEqualTo(CoworkerResponseProcessor.Status.HANDLED)

        val typeCaptor = argumentCaptor<String>()
        val varsCaptor = argumentCaptor<Map<String, Any>>()
        verify(resumeService).resume(eq(correlationId), typeCaptor.capture(), varsCaptor.capture())
        assertThat(typeCaptor.firstValue).isEqualTo("nl.valtimo.coworker.chat-response")
        assertThat(varsCaptor.firstValue)
            .containsEntry("coworkerSuccess", true)
            .containsEntry("coworkerContent", "Answer")
            .containsEntry("coworkerCorrelationId", correlationId)
        verify(processedRepository).save(any<ProcessedCoworker>())
    }

    @Test
    fun `returns UNMATCHED and does not mark processed when nothing is waiting`() {
        whenever(processedRepository.existsById(any())).thenReturn(false)
        whenever(resumeService.resume(any(), any(), any())).thenReturn(false)

        val outcome =
            processor.process(
                """{"id":"event-2","type":"nl.valtimo.coworker.chat-response","source":"urn:test","data":{"correlationId":"$correlationId","success":true}}""",
            )

        assertThat(outcome.status).isEqualTo(CoworkerResponseProcessor.Status.UNMATCHED)
        assertThat(outcome.eventId).isEqualTo("event-2")
        verify(processedRepository, never()).save(any<ProcessedCoworker>())
    }

    @Test
    fun `maps error fields on a chat-error`() {
        whenever(processedRepository.existsById(any())).thenReturn(false)
        whenever(resumeService.resume(any(), any(), any())).thenReturn(true)

        processor.process(
            """{"id":"event-3","type":"nl.valtimo.coworker.chat-error","source":"urn:test","data":{"correlationId":"$correlationId","success":false,"error":"boom","errorCode":"TIMEOUT"}}""",
        )

        val varsCaptor = argumentCaptor<Map<String, Any>>()
        verify(resumeService).resume(eq(correlationId), eq("nl.valtimo.coworker.chat-error"), varsCaptor.capture())
        assertThat(varsCaptor.firstValue)
            .containsEntry("coworkerSuccess", false)
            .containsEntry("coworkerError", "boom")
            .containsEntry("coworkerErrorCode", "TIMEOUT")
    }

    @Test
    fun `ignores a foreign cloud event type`() {
        val outcome =
            processor.process(
                """{"id":"x","type":"com.example.other","source":"urn:test","data":{"correlationId":"c"}}""",
            )

        assertThat(outcome.status).isEqualTo(CoworkerResponseProcessor.Status.IGNORED)
        verify(resumeService, never()).resume(any(), any(), any())
    }

    @Test
    fun `ignores an already-processed event`() {
        whenever(processedRepository.existsById("dup")).thenReturn(true)

        val outcome =
            processor.process(
                """{"id":"dup","type":"nl.valtimo.coworker.chat-response","source":"urn:test","data":{"correlationId":"c","success":true}}""",
            )

        assertThat(outcome.status).isEqualTo(CoworkerResponseProcessor.Status.IGNORED)
        verify(resumeService, never()).resume(any(), any(), any())
    }

    @Test
    fun `ignores a response without a correlation id`() {
        whenever(processedRepository.existsById(any())).thenReturn(false)

        val outcome =
            processor.process(
                """{"id":"event-4","type":"nl.valtimo.coworker.chat-response","source":"urn:test","data":{"success":true}}""",
            )

        assertThat(outcome.status).isEqualTo(CoworkerResponseProcessor.Status.IGNORED)
        verify(resumeService, never()).resume(any(), any(), any())
    }
}
