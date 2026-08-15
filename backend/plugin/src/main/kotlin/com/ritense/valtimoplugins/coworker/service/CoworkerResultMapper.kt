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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.valtimoplugins.coworker.domain.CoworkerResultMapping
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Picks values out of a CoWorker's JSON answer and routes them to their configured
 * targets: process variables (`pv:`) and case-document fields (`doc:`).
 *
 * Nothing here throws. A CoWorker can always answer in an unexpected shape, and a
 * failed mapping must never leave the process waiting forever or turn the reply into
 * a message that is retried indefinitely. Problems are collected in [Result.error]
 * instead, which the caller exposes as the `coworkerMappingError` process variable so
 * a process can branch on it.
 */
open class CoworkerResultMapper(
    private val objectMapper: ObjectMapper,
) {
    /**
     * [processVariables] are keyed by variable name, [documentValues] by their full
     * `doc:` expression (the shape `ValueResolverService.handleValues` expects).
     */
    data class Result(
        val processVariables: Map<String, Any> = emptyMap(),
        val documentValues: Map<String, Any> = emptyMap(),
        val error: String? = null,
    ) {
        val isEmpty: Boolean get() = processVariables.isEmpty() && documentValues.isEmpty()
    }

    open fun map(
        mappings: List<CoworkerResultMapping>?,
        content: String?,
    ): Result {
        val configured = mappings?.filter { !it.source.isNullOrBlank() && !it.target.isNullOrBlank() }
        if (configured.isNullOrEmpty()) return Result()

        val answer =
            parseJson(content)
                ?: return Result(error = "The CoWorker answer is not JSON, so no values could be mapped")

        val processVariables = mutableMapOf<String, Any>()
        val documentValues = mutableMapOf<String, Any>()
        val errors = mutableListOf<String>()

        configured.forEach { mapping ->
            val source = mapping.source!!.trim()
            val target = mapping.target!!.trim()
            val node = answer.at(pointerOf(source))
            if (node.isMissingNode || node.isNull) {
                errors += "'$source' is not in the answer"
                return@forEach
            }
            when {
                target.startsWith(PROCESS_VARIABLE_PREFIX) -> {
                    val name = target.removePrefix(PROCESS_VARIABLE_PREFIX).trim()
                    if (name.isEmpty()) {
                        errors += "target '$target' has no variable name"
                    } else {
                        processVariables[name] =
                            valueOf(node)
                    }
                }
                target.startsWith(DOCUMENT_PREFIX) -> documentValues[target] = valueOf(node)
                else -> errors += "target '$target' is not supported (use pv: or doc:)"
            }
        }

        return Result(
            processVariables = processVariables,
            documentValues = documentValues,
            error = errors.joinToString("; ").ifBlank { null },
        )
    }

    /**
     * Parses the answer as JSON. Models like to wrap JSON in a ```json fence, so that
     * is stripped first. Returns `null` for anything that is not a JSON object or
     * array — a plain sentence has nothing to map from.
     */
    private fun parseJson(content: String?): JsonNode? {
        val trimmed = content?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val body =
            if (trimmed.startsWith(FENCE)) {
                trimmed
                    .removePrefix("$FENCE$JSON_FENCE_LANGUAGE")
                    .removePrefix(FENCE)
                    .removeSuffix(FENCE)
                    .trim()
            } else {
                trimmed
            }
        return try {
            objectMapper.readTree(body)?.takeIf { it.isContainerNode }
        } catch (e: Exception) {
            logger.debug(e) { "CoWorker answer is not JSON" }
            null
        }
    }

    /** Accepts both `/netto` and `netto` as a pointer into the answer. */
    private fun pointerOf(source: String): String = if (source.startsWith("/")) source else "/$source"

    /**
     * Scalars keep their JSON type so a number lands in the case document as a number.
     * Objects and arrays are written as their JSON text — predictable, and enough for
     * the values a process usually branches on.
     */
    private fun valueOf(node: JsonNode): Any =
        when {
            node.isTextual -> node.asText()
            node.isNumber -> node.numberValue()
            node.isBoolean -> node.asBoolean()
            else -> objectMapper.writeValueAsString(node)
        }

    companion object {
        private val logger = KotlinLogging.logger {}
        private const val PROCESS_VARIABLE_PREFIX = "pv:"
        private const val DOCUMENT_PREFIX = "doc:"
        private const val FENCE = "```"
        private const val JSON_FENCE_LANGUAGE = "json"
    }
}
