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

package com.ritense.valtimoplugins.coworker

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.valtimoplugins.coworker.service.PromptTemplateResolver
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.delegate.DelegateExecution

class PromptTemplateResolverTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val valueResolverService = mock<ValueResolverService>()
    private val resolver = PromptTemplateResolver(valueResolverService, objectMapper)

    private val execution =
        mock<DelegateExecution>().also {
            whenever(it.id).thenReturn("exec-1")
            whenever(it.processInstanceId).thenReturn("proc-1")
        }

    /** Makes every requested expression supported and resolvable via [values]. */
    private fun stubResolver(values: Map<String, Any>) {
        whenever(valueResolverService.supportsValue(any())).thenReturn(true)
        whenever(valueResolverService.resolveValues(eq("proc-1"), any<DelegateExecution>(), any()))
            .thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val requested = invocation.arguments[2] as Collection<String>
                requested.mapNotNull { key -> values[key]?.let { key to it } }.toMap()
            }
    }

    @Test
    fun `substitutes process variable and document placeholders`() {
        stubResolver(mapOf("doc:/vraag" to "Mag ik een vergunning?", "pv:klantnummer" to "12345"))

        val result = resolver.resolve("Beoordeel {{doc:/vraag}} op spoed. Klant: {{pv:klantnummer}}", execution)

        assertThat(result).isEqualTo("Beoordeel Mag ik een vergunning? op spoed. Klant: 12345")
    }

    @Test
    fun `tolerates whitespace inside the placeholder`() {
        stubResolver(mapOf("pv:naam" to "Asha"))

        assertThat(resolver.resolve("Hallo {{ pv:naam }}", execution)).isEqualTo("Hallo Asha")
    }

    @Test
    fun `resolves each distinct expression once even when repeated`() {
        stubResolver(mapOf("pv:naam" to "Asha"))

        val result = resolver.resolve("{{pv:naam}} en nogmaals {{pv:naam}}", execution)

        assertThat(result).isEqualTo("Asha en nogmaals Asha")
        val captor = argumentCaptor<Collection<String>>()
        verify(valueResolverService).resolveValues(eq("proc-1"), any<DelegateExecution>(), captor.capture())
        assertThat(captor.firstValue).containsExactly("pv:naam")
    }

    @Test
    fun `renders non-string values`() {
        stubResolver(
            mapOf(
                "pv:bedrag" to 42,
                "pv:akkoord" to true,
                "pv:regels" to listOf("a", "b"),
            ),
        )

        val result = resolver.resolve("{{pv:bedrag}} {{pv:akkoord}} {{pv:regels}}", execution)

        assertThat(result).isEqualTo("""42 true ["a","b"]""")
    }

    @Test
    fun `leaves a prompt without placeholders untouched and never calls the resolver`() {
        val prompt = "Vat deze zaak samen."

        assertThat(resolver.resolve(prompt, execution)).isEqualTo(prompt)

        verify(valueResolverService, never()).resolveValues(any(), any<DelegateExecution>(), any())
    }

    @Test
    fun `leaves literal braces that are not resolver expressions untouched`() {
        val prompt = """Antwoord als JSON: {{"netto": 0}}"""

        assertThat(resolver.resolve(prompt, execution)).isEqualTo(prompt)

        verify(valueResolverService, never()).resolveValues(any(), any<DelegateExecution>(), any())
    }

    @Test
    fun `passes through null and blank prompts`() {
        assertThat(resolver.resolve(null, execution)).isNull()
        assertThat(resolver.resolve("  ", execution)).isEqualTo("  ")
    }

    @Test
    fun `fails on an unknown resolver prefix`() {
        whenever(valueResolverService.supportsValue(any())).thenReturn(false)
        whenever(valueResolverService.getValueResolvers()).thenReturn(listOf("pv", "doc"))

        val ex =
            runCatching { resolver.resolve("Beoordeel {{typo:/vraag}}", execution) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageContaining("typo:/vraag")
    }

    @Test
    fun `fails when a placeholder cannot be resolved`() {
        stubResolver(emptyMap())

        val ex =
            runCatching { resolver.resolve("Beoordeel {{doc:/onbekend}}", execution) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex).hasMessageContaining("doc:/onbekend")
    }
}
