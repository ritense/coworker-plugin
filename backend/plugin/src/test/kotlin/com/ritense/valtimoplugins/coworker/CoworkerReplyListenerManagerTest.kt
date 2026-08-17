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

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.valtimoplugins.coworker.domain.CoworkerRabbitMqProperties
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListener
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListenerManager
import com.ritense.valtimoplugins.coworker.transport.CoworkerConnectionFactoryProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class CoworkerReplyListenerManagerTest : BaseTest() {
    private val objectMapper = jacksonObjectMapper()
    private val pluginConfigurationRepository = mock<PluginConfigurationRepository>()
    private val connectionFactoryProvider = mock<CoworkerConnectionFactoryProvider>()
    private val replyListener = mock<CoworkerReplyListener>()

    private fun manager(listenersEnabled: Boolean = true) =
        CoworkerReplyListenerManager(
            pluginConfigurationRepository,
            connectionFactoryProvider,
            replyListener,
            listenersEnabled,
        )

    /** A stored plugin configuration whose (already decrypted) properties are [json]. */
    private fun configuration(json: String): PluginConfiguration =
        mock<PluginConfiguration>().also {
            whenever(it.properties).thenReturn(objectMapper.readTree(json) as ObjectNode)
            whenever(it.title).thenReturn("Coworker configuration")
        }

    @Test
    fun `derives the reply queue and the broker connection from a configuration`() {
        val subscription =
            manager().subscriptionOf(
                configuration(
                    """
                    {
                      "replyQueue": "coworker-plugin.reply",
                      "rabbitMqHost": "broker.example.nl",
                      "rabbitMqPort": 5671,
                      "rabbitMqVirtualHost": "/coworker",
                      "rabbitMqUsername": "coworker-user",
                      "rabbitMqPassword": "s3cret"
                    }
                    """.trimIndent(),
                ),
            )

        assertThat(subscription).isNotNull
        assertThat(subscription!!.replyQueue).isEqualTo("coworker-plugin.reply")
        assertThat(subscription.connection)
            .isEqualTo(
                CoworkerRabbitMqProperties(
                    host = "broker.example.nl",
                    port = 5671,
                    virtualHost = "/coworker",
                    username = "coworker-user",
                    password = "s3cret",
                ),
            )
    }

    @Test
    fun `a configuration without rabbitmq fields falls back to the application connection`() {
        val subscription =
            manager().subscriptionOf(configuration("""{"replyQueue": "coworker-plugin.reply"}"""))

        assertThat(subscription!!.connection).isEqualTo(CoworkerRabbitMqProperties.APPLICATION_DEFAULTS)
        assertThat(subscription.connection.overridesApplicationDefaults).isFalse()
    }

    @Test
    fun `blank rabbitmq fields are treated as unset`() {
        val subscription =
            manager().subscriptionOf(
                configuration(
                    """{"replyQueue": "r", "rabbitMqHost": "", "rabbitMqUsername": "  ", "rabbitMqPassword": ""}""",
                ),
            )

        assertThat(subscription!!.connection).isEqualTo(CoworkerRabbitMqProperties.APPLICATION_DEFAULTS)
    }

    @Test
    fun `a port sent as a string is still read as a number`() {
        val subscription =
            manager().subscriptionOf(configuration("""{"replyQueue": "r", "rabbitMqPort": "5671"}"""))

        assertThat(subscription!!.connection.port).isEqualTo(5671)
    }

    @Test
    fun `a configuration without a reply queue is skipped`() {
        assertThat(manager().subscriptionOf(configuration("""{"source": "urn:test"}"""))).isNull()
        assertThat(manager().subscriptionOf(configuration("""{"replyQueue": ""}"""))).isNull()
    }

    @Test
    fun `two configurations on the same queue and broker collapse into one subscription`() {
        val json = """{"replyQueue": "shared.reply", "rabbitMqUsername": "u", "rabbitMqPassword": "p"}"""

        val first = manager().subscriptionOf(configuration(json))
        val second = manager().subscriptionOf(configuration(json))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `no listeners are started when the application disables them`() {
        manager(listenersEnabled = false).synchronize()

        verify(pluginConfigurationRepository, never()).findByPluginDefinitionKey(any())
        verify(connectionFactoryProvider, never()).connectionFactory(any())
    }
}
