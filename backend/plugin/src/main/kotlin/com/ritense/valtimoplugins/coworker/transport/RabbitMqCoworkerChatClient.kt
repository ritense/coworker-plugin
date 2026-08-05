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

package com.ritense.valtimoplugins.coworker.transport

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.valtimoplugins.coworker.domain.ChatRequestCloudEvent
import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Asynchronous CoWorker chat transport over RabbitMQ, used by the
 * `publish-coworker` action. Publishes a `chat-request` CloudEvent to the request
 * queue (default exchange, queue name as routing key) and returns the generated
 * CloudEvent id so the reply can be correlated.
 *
 * Sends raw JSON bytes with `content-type: application/json` to match what the
 * server consumes today and to avoid double-encoding by any
 * `Jackson2JsonMessageConverter` on the [RabbitTemplate] — mirrors
 * `coworker-client`'s `CoworkerRequestPublisher`.
 */
open class RabbitMqCoworkerChatClient(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Publishes the `chat-request` and returns its generated CloudEvent id.
     *
     * @param request the payload; its `replyTo` must be the caller's own reply queue.
     * @param source a `urn:`-prefixed CloudEvent source (NL GOV URN).
     * @param requestRoutingKey where the request is sent (e.g. `vcs.chat.in`).
     */
    open fun publish(
        request: ChatRequestData,
        source: String,
        requestRoutingKey: String,
    ): String {
        val cloudEventId = UUID.randomUUID().toString()
        val event =
            ChatRequestCloudEvent(
                id = cloudEventId,
                source = source,
                time = Instant.now().toString(),
                data = request,
            )

        val json = objectMapper.writeValueAsString(event)
        logger.info {
            "Publishing chat-request cloudEvent.id=$cloudEventId coworkerId=${request.coworkerId} to '$requestRoutingKey'"
        }

        val properties =
            MessageProperties().apply {
                contentType = "application/json"
                contentEncoding = "UTF-8"
            }
        rabbitTemplate.send(
            requestRoutingKey,
            Message(json.toByteArray(StandardCharsets.UTF_8), properties),
        )

        return cloudEventId
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
