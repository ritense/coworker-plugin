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

package com.ritense.valtimoplugins.coworker.web.rest

import com.ritense.authorization.annotation.RunWithoutAuthorization
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimo.contract.domain.ValtimoMediaType.APPLICATION_JSON_UTF8_VALUE
import com.ritense.valtimoplugins.coworker.domain.CoworkerOption
import com.ritense.valtimoplugins.coworker.service.CoworkerManagementService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Admin/config-time endpoint that lists the coworkers of a given plugin
 * configuration, for the `chat-coworker` "Coworker" dropdown. The Angular config
 * component calls this rather than the CoWorker server directly, because the
 * server needs HTTP Basic auth whose password is a plugin secret only the backend
 * can decrypt (via [CoworkerManagementService]).
 */
@RestController
@SkipComponentScan
@RequestMapping("/api/management", produces = [APPLICATION_JSON_UTF8_VALUE])
class CoworkerManagementResource(
    private val coworkerManagementService: CoworkerManagementService,
) {
    @RunWithoutAuthorization
    @GetMapping("/v1/coworker/{pluginConfigurationId}/coworkers")
    fun getCoworkers(
        @PathVariable pluginConfigurationId: String,
    ): ResponseEntity<List<CoworkerOption>> =
        ResponseEntity.ok(coworkerManagementService.getCoworkers(pluginConfigurationId))
}
