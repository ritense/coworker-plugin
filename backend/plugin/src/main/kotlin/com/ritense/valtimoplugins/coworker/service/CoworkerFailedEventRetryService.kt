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

import com.ritense.authorization.annotation.RunWithoutAuthorization
import com.ritense.valtimoplugins.coworker.repository.CoworkerFailedEventRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Periodically retries CoWorker replies that could not be matched to a waiting
 * BPMN activity when they first arrived (the response beat the process to its
 * receive step). Runs hourly by default; configure via
 * `valtimo.coworker.retry-cron`.
 *
 * Each row is re-run through [CoworkerResponseProcessor]:
 * - HANDLED  → the process resumed; drop the row.
 * - IGNORED  → it will never succeed (e.g. now a duplicate); drop the row.
 * - UNMATCHED→ still nothing waiting; bump the attempt counter and keep it.
 */
open class CoworkerFailedEventRetryService(
    private val failedEventRepository: CoworkerFailedEventRepository,
    private val processor: CoworkerResponseProcessor,
) {
    @RunWithoutAuthorization
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Scheduled(cron = "\${valtimo.coworker.retry-cron:0 0 * * * *}")
    open fun retryFailedEvents() {
        val failed = failedEventRepository.findAll()
        if (failed.isEmpty()) return

        logger.info { "Retrying ${failed.size} unmatched coworker response(s)" }
        failed.forEach { event ->
            val outcome =
                try {
                    processor.process(event.payload)
                } catch (e: Exception) {
                    logger.error(e) { "Retry of coworker response '${event.eventId}' threw; keeping for next run" }
                    null
                }

            when (outcome?.status) {
                CoworkerResponseProcessor.Status.HANDLED -> {
                    logger.info { "Retried coworker response '${event.eventId}' resumed a process; removing" }
                    failedEventRepository.delete(event)
                }

                CoworkerResponseProcessor.Status.IGNORED -> {
                    logger.info { "Coworker response '${event.eventId}' is no longer processable; removing" }
                    failedEventRepository.delete(event)
                }

                else -> {
                    event.attempts += 1
                    event.lastAttemptAt = Instant.now()
                    failedEventRepository.save(event)
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
