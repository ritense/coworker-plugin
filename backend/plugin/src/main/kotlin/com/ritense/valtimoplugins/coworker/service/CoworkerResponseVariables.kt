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

package com.ritense.valtimoplugins.coworker.service

import com.ritense.valtimoplugins.coworker.domain.ChatResponseData
import com.ritense.valtimoplugins.coworker.domain.CoworkerEventType

/**
 * Maps a CoWorker reply onto the `coworker*` process variables. Shared by the
 * asynchronous reply path ([CoworkerResponseProcessor]) and the synchronous
 * `chat-coworker` service-task action so both surface an identical set of
 * variables to BPMN regardless of which transport delivered the reply.
 */
object CoworkerResponseVariables {
    /** The CoWorker's answer. */
    const val VAR_CONTENT = "coworkerContent"

    /** Why the configured result mapping did not (fully) succeed; absent when it did. */
    const val VAR_MAPPING_ERROR = "coworkerMappingError"

    fun from(data: ChatResponseData): Map<String, Any> =
        build(
            eventId = data.messageId,
            correlationId = data.correlationId,
            eventType = eventTypeFor(data.success),
            data = data,
        )

    fun eventTypeFor(success: Boolean): String =
        if (success) CoworkerEventType.CHAT_RESPONSE.type else CoworkerEventType.CHAT_ERROR.type

    fun build(
        eventId: String?,
        correlationId: String?,
        eventType: String,
        data: ChatResponseData,
    ): Map<String, Any> {
        val variables =
            mutableMapOf<String, Any>(
                "coworkerType" to eventType,
                "coworkerSuccess" to data.success,
            )
        correlationId?.let { variables["coworkerCorrelationId"] = it }
        eventId?.let { variables["coworkerEventId"] = it }
        data.content?.let { variables[VAR_CONTENT] = it }
        data.error?.let { variables["coworkerError"] = it }
        data.errorCode?.let { variables["coworkerErrorCode"] = it }
        return variables
    }
}
