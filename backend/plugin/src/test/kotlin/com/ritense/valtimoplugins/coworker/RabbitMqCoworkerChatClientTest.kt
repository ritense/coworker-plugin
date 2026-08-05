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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.CoworkerEventType
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.springframework.amqp.core.Message
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.nio.charset.StandardCharsets

class RabbitMqCoworkerChatClientTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `publish sends a chat-request cloud event as raw json with the correct envelope`() {
        val rabbitTemplate = mock<RabbitTemplate>()
        val client = RabbitMqCoworkerChatClient(rabbitTemplate, objectMapper)

        val request =
            ChatRequestData(
                coworkerId = "coworker-1",
                caseId = "case-1",
                userPrompt = "Summarize this case",
                replyTo = "coworker-plugin.reply",
            )

        val cloudEventId =
            client.publish(
                request = request,
                source = "urn:nld:oin:00000000000000000001:systeem:coworker-plugin",
                requestRoutingKey = "vcs.chat.in",
            )

        val routingKeyCaptor = argumentCaptor<String>()
        val messageCaptor = argumentCaptor<Message>()
        verify(rabbitTemplate).send(routingKeyCaptor.capture(), messageCaptor.capture())

        assertThat(routingKeyCaptor.firstValue).isEqualTo("vcs.chat.in")

        val message = messageCaptor.firstValue
        assertThat(message.messageProperties.contentType).isEqualTo("application/json")

        val body = objectMapper.readTree(String(message.body, StandardCharsets.UTF_8))
        assertThat(body["specversion"].asText()).isEqualTo("1.0")
        assertThat(body["type"].asText()).isEqualTo(CoworkerEventType.CHAT_REQUEST.type)
        assertThat(body["source"].asText()).isEqualTo("urn:nld:oin:00000000000000000001:systeem:coworker-plugin")
        assertThat(body["id"].asText()).isEqualTo(cloudEventId)
        assertThat(body["time"].isMissingNode).isFalse()

        val data = body["data"]
        assertThat(data["coworkerId"].asText()).isEqualTo("coworker-1")
        assertThat(data["caseId"].asText()).isEqualTo("case-1")
        assertThat(data["userPrompt"].asText()).isEqualTo("Summarize this case")
        assertThat(data["replyTo"].asText()).isEqualTo("coworker-plugin.reply")
        // The isValid() helper must not leak into the wire payload.
        assertThat(data.has("isValid")).isFalse()
        assertThat(data.has("valid")).isFalse()
    }

    @Test
    fun `publish sends to the provided routing key and returns the envelope id`() {
        val rabbitTemplate = mock<RabbitTemplate>()
        val client = RabbitMqCoworkerChatClient(rabbitTemplate, objectMapper)

        val request = ChatRequestData(coworkerId = "c", caseId = "case-1", userPrompt = "hi", replyTo = "reply")

        val cloudEventId = client.publish(request, source = "urn:test", requestRoutingKey = "custom.queue")

        val messageCaptor = argumentCaptor<Message>()
        verify(rabbitTemplate).send(eq("custom.queue"), messageCaptor.capture())
        val body = objectMapper.readTree(String(messageCaptor.firstValue.body, StandardCharsets.UTF_8))
        assertThat(body["id"].asText()).isEqualTo(cloudEventId)
        assertThat(body["source"].asText()).isEqualTo("urn:test")
    }

    @Test
    fun `chat-request is valid with a user prompt`() {
        val data = ChatRequestData(coworkerId = "c", caseId = "x", userPrompt = "hi", replyTo = "r")
        assertThat(data.isValid()).isTrue()
    }

    @Test
    fun `chat-request is valid with expertise and input`() {
        val input: JsonNode = objectMapper.readTree("""{"k":"v"}""")
        val data =
            ChatRequestData(coworkerId = "c", caseId = "x", expertiseId = "e", input = input, replyTo = "r")
        assertThat(data.isValid()).isTrue()
    }

    @Test
    fun `chat-request is invalid without prompt or expertise input`() {
        val data = ChatRequestData(coworkerId = "c", caseId = "x", replyTo = "r")
        assertThat(data.isValid()).isFalse()
    }
}
