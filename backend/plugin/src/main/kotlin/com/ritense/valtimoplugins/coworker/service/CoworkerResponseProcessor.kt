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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.valtimoplugins.coworker.domain.ChatResponseCloudEvent
import com.ritense.valtimoplugins.coworker.domain.CoworkerEventType
import com.ritense.valtimoplugins.coworker.domain.ProcessedCoworker
import com.ritense.valtimoplugins.coworker.repository.ProcessedCoworkerRepository
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Parses and processes a raw CoWorker reply, driving [CoworkerProcessResumeService].
 * Shared by the live reply listener and the scheduled retry of failed events, so
 * both take exactly the same path.
 *
 * Hardening (mirrors `coworker-client`): known-`type` filter that ignores foreign
 * CloudEvents on the shared broker, a lenient blank-`type` path, and dedup by
 * CloudEvent id via `coworker_processed` (only marked processed once handled).
 */
open class CoworkerResponseProcessor(
    private val objectMapper: ObjectMapper,
    private val processedCoworkerRepository: ProcessedCoworkerRepository,
    private val resumeService: CoworkerProcessResumeService,
) {
    /** Outcome of processing a single reply. */
    enum class Status {
        /** Resumed/started at least one activity. Terminal. */
        HANDLED,

        /** Not processable and never will be (parse error, foreign type, no correlationId, duplicate). Terminal. */
        IGNORED,

        /** Valid, but nothing is waiting yet — worth retrying later. */
        UNMATCHED,
    }

    data class Outcome(
        val status: Status,
        val eventId: String?,
    )

    open fun process(raw: String): Outcome {
        val event =
            try {
                objectMapper.readValue(raw, ChatResponseCloudEvent::class.java)
            } catch (e: Exception) {
                logger.error(e) { "Failed to parse CloudEvent from coworker reply" }
                return Outcome(Status.IGNORED, null)
            }

        val eventId = event.id.ifBlank { null }

        // Known-type filter: ignore foreign events on the shared broker. A blank
        // type is tolerated (lenient path) since the server does not always set it.
        if (event.type.isNotBlank() && !CoworkerEventType.isKnown(event.type)) {
            logger.debug { "Ignoring foreign CloudEvent of type '${event.type}'" }
            return Outcome(Status.IGNORED, eventId)
        }

        if (eventId != null && processedCoworkerRepository.existsById(eventId)) {
            logger.debug { "Skipping already-processed coworker response '$eventId'" }
            return Outcome(Status.IGNORED, eventId)
        }

        val data = event.data
        val correlationId = data?.correlationId
        if (correlationId.isNullOrBlank()) {
            logger.warn { "Coworker response '$eventId' has no data.correlationId; cannot correlate" }
            return Outcome(Status.IGNORED, eventId)
        }

        val eventType = event.type.ifBlank { CoworkerResponseVariables.eventTypeFor(data.success) }
        val variables = CoworkerResponseVariables.build(eventId, correlationId, eventType, data)

        val handled = resumeService.resume(correlationId, eventType, variables)
        if (!handled) {
            logger.info {
                "No waiting activity for correlationId '$correlationId' (event '$eventId'); will retry later"
            }
            return Outcome(Status.UNMATCHED, eventId)
        }

        if (eventId != null) {
            processedCoworkerRepository.save(ProcessedCoworker(eventId = eventId))
        }
        return Outcome(Status.HANDLED, eventId)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
