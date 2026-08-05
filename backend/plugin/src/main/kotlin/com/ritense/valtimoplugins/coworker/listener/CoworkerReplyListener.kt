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

package com.ritense.valtimoplugins.coworker.listener

import com.ritense.authorization.annotation.RunWithoutAuthorization
import com.ritense.valtimoplugins.coworker.domain.CoworkerFailedEvent
import com.ritense.valtimoplugins.coworker.repository.CoworkerFailedEventRepository
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseProcessor
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets

/**
 * Listens on the plugin's own reply queue for CoWorker `chat-response` /
 * `chat-error` events. Delegates parsing + correlation + resume to
 * [CoworkerResponseProcessor]; a valid reply that has no waiting activity yet is
 * stored in `coworker_failed_event` for the scheduled retry
 * ([com.ritense.valtimoplugins.coworker.service.CoworkerFailedEventRetryService]).
 *
 * Reads raw bytes so the payload is parsed as plain JSON regardless of the
 * container's message converter — matching the server's wire format.
 */
open class CoworkerReplyListener(
    private val processor: CoworkerResponseProcessor,
    private val failedEventRepository: CoworkerFailedEventRepository,
) {
    @RunWithoutAuthorization
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @RabbitListener(queues = ["\${valtimo.coworker.reply-queue:coworker-plugin.reply}"])
    open fun onReply(message: Message) {
        val raw = String(message.body, StandardCharsets.UTF_8)
        logger.debug { "Received on coworker reply queue: ${raw.take(200)}" }

        val outcome = processor.process(raw)
        if (outcome.status == CoworkerResponseProcessor.Status.UNMATCHED && outcome.eventId != null) {
            if (!failedEventRepository.existsById(outcome.eventId)) {
                failedEventRepository.save(CoworkerFailedEvent(eventId = outcome.eventId, payload = raw))
                logger.info { "Stored unmatched coworker response '${outcome.eventId}' for retry" }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
