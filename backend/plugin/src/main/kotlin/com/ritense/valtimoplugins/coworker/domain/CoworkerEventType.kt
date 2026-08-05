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

/**
 * The closed set of CoWorker CloudEvent `type`s this plugin speaks.
 *
 * Keep this in lockstep with the frontend event set (see `models/config.ts`) so
 * the dropdown options and backend validation never drift apart.
 */
enum class CoworkerEventType(
    val type: String,
) {
    CHAT_REQUEST("nl.valtimo.coworker.chat-request"),
    CHAT_RESPONSE("nl.valtimo.coworker.chat-response"),
    CHAT_ERROR("nl.valtimo.coworker.chat-error"),
    ;

    companion object {
        fun fromType(type: String): CoworkerEventType? = entries.firstOrNull { it.type == type }

        fun isKnown(type: String?): Boolean = type != null && fromType(type) != null
    }
}
