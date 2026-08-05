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
 * An option for the `chat-coworker` "Coworker" dropdown: [id] is the value sent as
 * `coworkerId` in a chat request (the coworker's UUID), [name] is the label.
 */
data class CoworkerOption(
    val id: String,
    val name: String,
)

/**
 * Lenient view of the CoWorker server's `CoworkerDto` (`GET /api/v1/coworkers`) —
 * only the fields the dropdown needs. `ignoreUnknown` so the many other fields
 * (model settings, guardrails, timestamps, …) never break parsing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CoworkerProfileResponse(
    val id: String = "",
    val name: String = "",
)
