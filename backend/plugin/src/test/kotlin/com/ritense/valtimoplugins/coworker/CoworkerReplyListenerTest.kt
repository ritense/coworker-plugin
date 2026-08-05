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

import com.ritense.valtimoplugins.coworker.domain.CoworkerFailedEvent
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListener
import com.ritense.valtimoplugins.coworker.repository.CoworkerFailedEventRepository
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseProcessor
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import java.nio.charset.StandardCharsets

class CoworkerReplyListenerTest : BaseTest() {
    private val processor = mock<CoworkerResponseProcessor>()
    private val failedEventRepository = mock<CoworkerFailedEventRepository>()

    private val listener = CoworkerReplyListener(processor, failedEventRepository)

    private fun message(json: String) = Message(json.toByteArray(StandardCharsets.UTF_8), MessageProperties())

    @Test
    fun `stores an unmatched reply for retry`() {
        whenever(processor.process(any()))
            .thenReturn(CoworkerResponseProcessor.Outcome(CoworkerResponseProcessor.Status.UNMATCHED, "event-1"))
        whenever(failedEventRepository.existsById("event-1")).thenReturn(false)

        listener.onReply(message("""{"id":"event-1"}"""))

        verify(failedEventRepository).save(any<CoworkerFailedEvent>())
    }

    @Test
    fun `does not store a handled reply`() {
        whenever(processor.process(any()))
            .thenReturn(CoworkerResponseProcessor.Outcome(CoworkerResponseProcessor.Status.HANDLED, "event-2"))

        listener.onReply(message("""{"id":"event-2"}"""))

        verify(failedEventRepository, never()).save(any<CoworkerFailedEvent>())
    }

    @Test
    fun `does not double-store an unmatched reply already queued for retry`() {
        whenever(processor.process(any()))
            .thenReturn(CoworkerResponseProcessor.Outcome(CoworkerResponseProcessor.Status.UNMATCHED, "event-3"))
        whenever(failedEventRepository.existsById("event-3")).thenReturn(true)

        listener.onReply(message("""{"id":"event-3"}"""))

        verify(failedEventRepository, never()).save(any<CoworkerFailedEvent>())
    }

    @Test
    fun `does not store an ignored reply`() {
        whenever(processor.process(any()))
            .thenReturn(CoworkerResponseProcessor.Outcome(CoworkerResponseProcessor.Status.IGNORED, "event-4"))

        listener.onReply(message("""{"id":"event-4","type":"com.example.other"}"""))

        verify(failedEventRepository, never()).save(any<CoworkerFailedEvent>())
        verify(failedEventRepository, never()).existsById(eq("event-4"))
    }
}
