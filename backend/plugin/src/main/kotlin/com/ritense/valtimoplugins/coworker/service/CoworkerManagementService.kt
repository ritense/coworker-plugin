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

import com.ritense.plugin.service.PluginService
import com.ritense.valtimoplugins.coworker.domain.CoworkerOption
import com.ritense.valtimoplugins.coworker.plugin.CoworkerPlugin
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID

/**
 * Backs the config-time "Coworker" dropdown of the `chat-coworker` action. Resolves
 * the selected plugin configuration (which decrypts the `coworkerPassword` secret)
 * and lists the coworkers from that configuration's CoWorker server.
 *
 * Returns an empty list rather than failing when no `coworkerUrl` is configured or
 * the server is unreachable, so the configuration screen stays usable.
 */
open class CoworkerManagementService(
    private val pluginService: PluginService,
    private val restCoworkerChatClient: RestCoworkerChatClient,
) {
    open fun getCoworkers(pluginConfigurationId: String): List<CoworkerOption> {
        val plugin = pluginService.createInstance<CoworkerPlugin>(UUID.fromString(pluginConfigurationId))
        if (plugin.coworkerUrl.isNullOrBlank()) {
            logger.debug { "No coworkerUrl configured for plugin '$pluginConfigurationId'; returning no coworkers" }
            return emptyList()
        }
        return try {
            restCoworkerChatClient.listCoworkers(plugin.coworkerUrl, plugin.coworkerUsername, plugin.coworkerPassword)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to list coworkers for plugin configuration '$pluginConfigurationId'" }
            emptyList()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
