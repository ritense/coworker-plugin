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

package com.ritense.valtimoplugins.coworker.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode

/**
 * Request body for the CoWorker server's **synchronous REST** chat endpoint
 * (`POST /api/v1/chat`). This is the server's plain `ChatRequest` DTO — NOT the
 * CloudEvent / [ChatRequestData] wire shape used over RabbitMQ. `streamResponse`
 * is pinned to `false` so the endpoint returns the full answer in a single
 * response body (see [ChatRestResponse]) rather than an SSE stream.
 */
data class ChatRestRequest(
    val coworkerId: String,
    val caseId: String,
    val userPrompt: String? = null,
    val expertiseId: String? = null,
    val input: JsonNode? = null,
    val sessionId: String? = null,
    val streamResponse: Boolean = false,
) {
    companion object {
        /**
         * Maps the shared [ChatRequestData] onto the REST endpoint's request shape.
         * `streamResponse` is pinned to `false` so the answer comes back in a single
         * response body rather than an SSE stream. The RabbitMQ-only `replyTo` and
         * `documents` fields have no equivalent on the synchronous REST path.
         */
        fun from(request: ChatRequestData): ChatRestRequest =
            ChatRestRequest(
                coworkerId = request.coworkerId,
                caseId = request.caseId,
                userPrompt = request.userPrompt,
                expertiseId = request.expertiseId,
                input = request.input,
                streamResponse = false,
            )
    }
}

/**
 * Response body from `POST /api/v1/chat`. Lenient parsing (`ignoreUnknown`) so
 * extra fields (e.g. `timestamp`) never break deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatRestResponse(
    val sessionId: String? = null,
    val messageId: String? = null,
    val content: String? = null,
    val tokenCount: Int? = null,
    val finishReason: String? = null,
) {
    /**
     * Maps the REST response onto the shared [ChatResponseData] shape so the
     * `chat-coworker` action writes the exact same `coworker*` process variables
     * as the asynchronous RabbitMQ reply path. There is no `correlationId` on the
     * synchronous path (the HTTP call itself is the correlation).
     */
    fun toChatResponseData(): ChatResponseData =
        ChatResponseData(
            correlationId = null,
            content = content,
            // The server sets finishReason to "error" on failure, "stop" otherwise.
            success = !finishReason.equals("error", ignoreCase = true),
            sessionId = sessionId,
            messageId = messageId,
        )
}
