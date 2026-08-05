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

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode

/**
 * Hand-rolled CloudEvents envelope for a CoWorker `chat-request`, serialized as
 * plain JSON to match the CoWorker server's current wire format (NL GOV
 * CloudEvents 1.1). Mirrors `coworker-client`'s DTOs deliberately — do NOT
 * introduce the `io.cloudevents` SDK here (that is a later version).
 */
data class ChatRequestCloudEvent(
    val specversion: String = "1.0",
    val id: String,
    val type: String = CoworkerEventType.CHAT_REQUEST.type,
    val source: String,
    val datacontenttype: String = "application/json",
    val time: String,
    val data: ChatRequestData,
)

data class ChatRequestData(
    val coworkerId: String,
    val caseId: String,
    val userPrompt: String? = null,
    val expertiseId: String? = null,
    val input: JsonNode? = null,
    val replyTo: String,
    val documents: List<DocumentData>? = null,
) {
    /**
     * Matches the server's `ChatRequestData.isValid()`: a request needs either a
     * `userPrompt` or an `expertiseId` + `input`. `@JsonIgnore` so it never leaks
     * into the serialized `data` payload.
     */
    @JsonIgnore
    fun isValid(): Boolean = !userPrompt.isNullOrBlank() || (expertiseId != null && input != null)
}

data class DocumentData(
    val fileName: String,
    val contentType: String,
    val content: String,
)
