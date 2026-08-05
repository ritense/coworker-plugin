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

import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.ChatRestResponse
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.web.client.RestClient

class RestCoworkerChatClientTest : BaseTest() {
    @Test
    fun `requires a coworkerUrl`() {
        val client = RestCoworkerChatClient(mock<RestClient.Builder>())
        val request = ChatRequestData(coworkerId = "c", caseId = "case-1", userPrompt = "hi", replyTo = "reply")

        assertThatThrownBy {
            client.chat(request, coworkerUrl = null, username = null, password = null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `maps a REST response onto ChatResponseData`() {
        val success = ChatRestResponse(content = "ok", finishReason = "stop", messageId = "m1").toChatResponseData()
        assertThat(success.success).isTrue()
        assertThat(success.content).isEqualTo("ok")
        assertThat(success.messageId).isEqualTo("m1")

        val error = ChatRestResponse(content = "boom", finishReason = "error").toChatResponseData()
        assertThat(error.success).isFalse()
    }
}
