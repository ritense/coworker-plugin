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
import com.ritense.valtimoplugins.coworker.domain.CoworkerResultMapping
import com.ritense.valtimoplugins.coworker.service.CoworkerResultMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoworkerResultMapperTest : BaseTest() {
    private val mapper = CoworkerResultMapper(jacksonObjectMapper())

    private val invoiceAnswer =
        """{"nettoBedrag": 100.50, "btwBedrag": 21, "totaalBedrag": 121.50, "leverancier": "Ritense", "akkoord": true}"""

    @Test
    fun `routes values to process variables and document fields`() {
        val result =
            mapper.map(
                listOf(
                    CoworkerResultMapping("/nettoBedrag", "doc:/factuur/netto"),
                    CoworkerResultMapping("/btwBedrag", "doc:/factuur/btw"),
                    CoworkerResultMapping("/totaalBedrag", "pv:totaalbedrag"),
                    CoworkerResultMapping("/leverancier", "pv:leverancier"),
                ),
                invoiceAnswer,
            )

        assertThat(result.error).isNull()
        assertThat(result.processVariables)
            .containsEntry("leverancier", "Ritense")
            .hasSize(2)
        assertThat(result.documentValues).containsOnlyKeys("doc:/factuur/netto", "doc:/factuur/btw")
        // Numbers keep their JSON type so the case document gets a number, not a string.
        assertThat(result.documentValues["doc:/factuur/btw"]).isEqualTo(21)
        // Decimals arrive as Double — a type the process engine can store natively.
        assertThat(result.processVariables["totaalbedrag"]).isEqualTo(121.50)
    }

    @Test
    fun `maps booleans and accepts a pointer without a leading slash`() {
        val result = mapper.map(listOf(CoworkerResultMapping("akkoord", "pv:akkoord")), invoiceAnswer)

        assertThat(result.error).isNull()
        assertThat(result.processVariables["akkoord"]).isEqualTo(true)
    }

    @Test
    fun `maps nested fields`() {
        val result =
            mapper.map(
                listOf(CoworkerResultMapping("/adres/straat", "pv:straat")),
                """{"adres": {"straat": "Dorpsstraat"}}""",
            )

        assertThat(result.processVariables["straat"]).isEqualTo("Dorpsstraat")
    }

    @Test
    fun `writes objects and arrays as JSON text`() {
        val result =
            mapper.map(
                listOf(CoworkerResultMapping("/regels", "pv:regels")),
                """{"regels": [{"code": "A"}]}""",
            )

        assertThat(result.processVariables["regels"]).isEqualTo("""[{"code":"A"}]""")
    }

    @Test
    fun `unwraps a fenced json answer`() {
        val fenced = "```json\n{\"netto\": 10}\n```"

        val result = mapper.map(listOf(CoworkerResultMapping("/netto", "pv:netto")), fenced)

        assertThat(result.error).isNull()
        assertThat(result.processVariables["netto"]).isEqualTo(10)
    }

    @Test
    fun `reports a plain text answer instead of failing`() {
        val result = mapper.map(listOf(CoworkerResultMapping("/netto", "pv:netto")), "Het netto bedrag is 100 euro.")

        assertThat(result.isEmpty).isTrue()
        assertThat(result.error).contains("not JSON")
    }

    @Test
    fun `reports a missing field but still maps the others`() {
        val result =
            mapper.map(
                listOf(
                    CoworkerResultMapping("/onbekend", "pv:onbekend"),
                    CoworkerResultMapping("/btwBedrag", "pv:btw"),
                ),
                invoiceAnswer,
            )

        assertThat(result.processVariables).containsOnlyKeys("btw")
        assertThat(result.error).contains("/onbekend")
    }

    @Test
    fun `reports an unsupported target`() {
        val result = mapper.map(listOf(CoworkerResultMapping("/btwBedrag", "case:/btw")), invoiceAnswer)

        assertThat(result.isEmpty).isTrue()
        assertThat(result.error).contains("case:/btw")
    }

    @Test
    fun `treats a null field as missing`() {
        val result = mapper.map(listOf(CoworkerResultMapping("/netto", "pv:netto")), """{"netto": null}""")

        assertThat(result.isEmpty).isTrue()
        assertThat(result.error).contains("/netto")
    }

    @Test
    fun `does nothing without mappings`() {
        assertThat(mapper.map(null, invoiceAnswer).isEmpty).isTrue()
        assertThat(mapper.map(emptyList(), invoiceAnswer).error).isNull()
        // Half-filled rows from the UI are ignored rather than reported.
        assertThat(mapper.map(listOf(CoworkerResultMapping("/netto", null)), invoiceAnswer).error).isNull()
    }

    @Test
    fun `reports a blank answer`() {
        val result = mapper.map(listOf(CoworkerResultMapping("/netto", "pv:netto")), null)

        assertThat(result.error).contains("not JSON")
    }
}
