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

import com.ritense.valtimoplugins.coworker.domain.ChatRequestData
import com.ritense.valtimoplugins.coworker.domain.ChatResponseData
import com.ritense.valtimoplugins.coworker.domain.ChatRestRequest
import com.ritense.valtimoplugins.coworker.domain.ChatRestResponse
import com.ritense.valtimoplugins.coworker.domain.CoworkerOption
import com.ritense.valtimoplugins.coworker.domain.CoworkerProfileResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.body

/**
 * REST client for the CoWorker server. Sends a chat-request to `POST /api/v1/chat`
 * (synchronous — answer returned inline as a [ChatResponseData]) and lists the
 * available coworkers via `GET /api/v1/coworkers` (for the config-time dropdown).
 *
 * Uses the server's plain [ChatRestRequest] / [ChatRestResponse] shape (not the
 * CloudEvent envelope) and authenticates with HTTP Basic. Builds a [RestClient] per
 * call from the shared [RestClient.Builder], applying the per-configuration
 * credentials. `chat` throws when no URL is configured or the call fails — the
 * `chat-coworker` action surfaces that as a BPMN incident (no RabbitMQ fallback).
 */
open class RestCoworkerChatClient(
    private val restClientBuilder: RestClient.Builder,
) {
    open fun chat(
        request: ChatRequestData,
        coworkerUrl: String?,
        username: String?,
        password: String?,
    ): ChatResponseData {
        val baseUrl =
            requireNotNull(coworkerUrl?.takeIf { it.isNotBlank() }) {
                "coworkerUrl must be configured to use the chat-coworker (REST) action"
            }
        val url = baseUrl.trimEnd('/') + CHAT_PATH
        logger.info { "POST $url coworkerId=${request.coworkerId} caseId=${request.caseId}" }

        val response =
            try {
                restClient(username, password)
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ChatRestRequest.from(request))
                    .retrieve()
                    .body<ChatRestResponse>()
            } catch (e: RestClientResponseException) {
                // Surface the server's status + response body so the BPMN incident is
                // actionable instead of an opaque 4xx/5xx.
                throw IllegalStateException(
                    "CoWorker REST chat to $url failed: ${e.statusCode} — ${e.responseBodyAsString}",
                    e,
                )
            } ?: throw IllegalStateException("Empty response body from CoWorker REST endpoint $url")

        return response.toChatResponseData()
    }

    /**
     * Lists the server's active coworkers as [CoworkerOption]s (value = id, label =
     * name) for the `chat-coworker` dropdown. Requires a configured URL.
     */
    open fun listCoworkers(
        coworkerUrl: String?,
        username: String?,
        password: String?,
    ): List<CoworkerOption> {
        val baseUrl =
            requireNotNull(coworkerUrl?.takeIf { it.isNotBlank() }) {
                "coworkerUrl must be configured to list coworkers"
            }
        val url = baseUrl.trimEnd('/') + COWORKERS_PATH + "?activeOnly=true"
        logger.info { "GET $url" }

        val profiles =
            restClient(username, password)
                .get()
                .uri(url)
                .retrieve()
                .body<List<CoworkerProfileResponse>>()
                ?: emptyList()

        return profiles.filter { it.id.isNotBlank() }.map { CoworkerOption(it.id, it.name) }
    }

    private fun restClient(
        username: String?,
        password: String?,
    ): RestClient =
        restClientBuilder
            .clone()
            .apply {
                if (!username.isNullOrBlank()) {
                    it.defaultHeaders { headers -> headers.setBasicAuth(username, password.orEmpty()) }
                }
            }.build()

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val CHAT_PATH = "/api/v1/chat"
        private const val COWORKERS_PATH = "/api/v1/coworkers"
    }
}
