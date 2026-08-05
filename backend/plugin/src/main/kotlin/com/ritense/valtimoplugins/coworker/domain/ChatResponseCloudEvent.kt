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

/**
 * Inbound CloudEvents envelope for a CoWorker `chat-response` / `chat-error`,
 * parsed from plain JSON. Lenient (`ignoreUnknown`, defaulted fields) to match
 * the server's current wire format and to tolerate foreign events on the shared
 * broker — mirrors `coworker-client`'s `CoworkerResponseListener` DTOs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponseCloudEvent(
    val specversion: String = "1.0",
    val id: String = "",
    val type: String = "",
    val source: String = "",
    val data: ChatResponseData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponseData(
    val correlationId: String? = null,
    val content: String? = null,
    val success: Boolean = true,
    val error: String? = null,
    val errorCode: String? = null,
    val sessionId: String? = null,
    val messageId: String? = null,
    val caseId: String? = null,
)
