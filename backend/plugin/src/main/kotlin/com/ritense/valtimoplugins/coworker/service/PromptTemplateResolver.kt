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
import com.ritense.valueresolver.ValueResolverService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution

/**
 * Fills `{{prefix:key}}` placeholders in a prompt with Valtimo value-resolver
 * values, so a prompt can mix free text with case data:
 *
 * ```
 * Beoordeel {{doc:/vraag}} op spoed. Klantnummer: {{pv:klantnummer}}
 * ```
 *
 * Valtimo's own value resolvers do not help here: `PluginService` resolves an
 * action property by splitting the **whole** value on its first `:` to find the
 * prefix, so `pv:foo` works but `text {{pv:foo}} text` never resolves. This class
 * therefore does the interpolation itself and delegates the actual lookup of each
 * expression to [ValueResolverService].
 *
 * `{{...}}` (not `${...}`) is deliberate: `${...}` already means "Spring
 * Environment property" on plugin *configuration* fields, and reusing it on action
 * fields would give the same syntax two meanings in one plugin.
 */
open class PromptTemplateResolver(
    private val valueResolverService: ValueResolverService,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Returns [template] with every placeholder replaced by its resolved value.
     * Blank templates and templates without placeholders are returned unchanged.
     *
     * Fails fast (rather than sending a half-empty prompt to the CoWorker) when a
     * placeholder uses an unknown resolver prefix or resolves to nothing.
     */
    open fun resolve(
        template: String?,
        execution: DelegateExecution,
    ): String? {
        if (template.isNullOrBlank()) return template

        val expressions = expressionsIn(template)
        if (expressions.isEmpty()) return template

        val unsupported = expressions.filterNot { valueResolverService.supportsValue(it) }
        require(unsupported.isEmpty()) {
            "Unknown value resolver prefix in prompt placeholder(s) ${unsupported.joinToString()}. " +
                "Available resolvers: ${valueResolverService.getValueResolvers().joinToString()}"
        }

        val resolved = valueResolverService.resolveValues(execution.processInstanceId, execution, expressions)
        val missing = expressions.filter { resolved[it] == null }
        require(missing.isEmpty()) {
            "Prompt placeholder(s) ${missing.joinToString()} could not be resolved for process instance " +
                "'${execution.processInstanceId}'"
        }

        logger.debug { "Resolved ${expressions.size} prompt placeholder(s) for execution '${execution.id}'" }
        return PLACEHOLDER.replace(template) { match ->
            val expression = match.groupValues[1].trim()
            // Anything that is not a resolver expression (e.g. a literal `{{` in an
            // example JSON snippet) is left exactly as the user typed it.
            resolved[expression]?.let { format(it) } ?: match.value
        }
    }

    /** The distinct resolver expressions used as placeholders in [template]. */
    private fun expressionsIn(template: String): Set<String> =
        PLACEHOLDER
            .findAll(template)
            .map { it.groupValues[1].trim() }
            .filter { EXPRESSION.matches(it) }
            .toSet()

    /** Renders a resolved value as prompt text; objects and arrays go in as JSON. */
    private fun format(value: Any): String =
        when {
            value is String -> value
            value is Number || value is Boolean -> value.toString()
            value is JsonNode && value.isValueNode -> value.asText()
            else -> objectMapper.writeValueAsString(value)
        }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** A `{{ ... }}` placeholder; the inner text is captured. */
        private val PLACEHOLDER = Regex("""\{\{([^{}]*)}}""")

        /**
         * A value-resolver expression: `prefix:key`, e.g. `pv:total` or `doc:/a/b`.
         * Text that does not have this shape is not treated as a placeholder, so a
         * prompt may still contain literal braces.
         */
        private val EXPRESSION = Regex("""^[A-Za-z_][A-Za-z0-9_-]*:.+$""")
    }
}
