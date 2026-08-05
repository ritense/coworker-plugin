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

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A CoWorker reply that was valid but could not be matched to a waiting BPMN
 * activity yet (typically the response arrived before the process reached its
 * receive step). Retried on a schedule by
 * [com.ritense.valtimoplugins.coworker.service.CoworkerFailedEventRetryService].
 */
@Entity
@Table(name = "coworker_failed_event")
class CoworkerFailedEvent(
    @Id
    @Column(name = "event_id")
    val eventId: String,
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    val payload: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "last_attempt_at", nullable = false)
    var lastAttemptAt: Instant = Instant.now(),
    @Column(name = "attempts", nullable = false)
    var attempts: Int = 1,
)
