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

import com.ritense.valtimoplugins.coworker.domain.CoworkerRabbitMqProperties
import com.ritense.valtimoplugins.coworker.transport.CoworkerConnectionFactoryProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import javax.net.ssl.SSLContext

class CoworkerConnectionFactoryProviderTest : BaseTest() {
    // Stands in for the host app's spring.rabbitmq.* connection.
    private val applicationConnectionFactory =
        CachingConnectionFactory().apply {
            setHost("app-host")
            port = 5672
            setVirtualHost("/app")
            setUsername("app-user")
        }

    private val provider = CoworkerConnectionFactoryProvider(applicationConnectionFactory)

    @AfterEach
    fun tearDown() {
        provider.destroy()
        applicationConnectionFactory.destroy()
    }

    @Test
    fun `a configuration without rabbitmq fields reuses the application connection`() {
        val connectionFactory = provider.connectionFactory(CoworkerRabbitMqProperties.APPLICATION_DEFAULTS)

        assertThat(connectionFactory).isSameAs(applicationConnectionFactory)
    }

    @Test
    fun `credentials alone keep the application host, port and virtual host`() {
        val properties = CoworkerRabbitMqProperties.of(null, null, null, "coworker-user", "s3cret")

        val connectionFactory = provider.connectionFactory(properties)

        assertThat(connectionFactory).isNotSameAs(applicationConnectionFactory)
        assertThat(connectionFactory.host).isEqualTo("app-host")
        assertThat(connectionFactory.port).isEqualTo(5672)
        assertThat(connectionFactory.virtualHost).isEqualTo("/app")
        assertThat(connectionFactory.username).isEqualTo("coworker-user")
    }

    @Test
    fun `a fully specified configuration overrides every application default`() {
        val properties =
            CoworkerRabbitMqProperties.of("broker.example.nl", 5671, "/coworker", "coworker-user", "s3cret")

        val connectionFactory = provider.connectionFactory(properties)

        assertThat(connectionFactory.host).isEqualTo("broker.example.nl")
        assertThat(connectionFactory.port).isEqualTo(5671)
        assertThat(connectionFactory.virtualHost).isEqualTo("/coworker")
        assertThat(connectionFactory.username).isEqualTo("coworker-user")
    }

    @Test
    fun `configurations pointing at the same broker share one connection`() {
        val first = CoworkerRabbitMqProperties.of("broker.example.nl", 5672, "/", "user", "s3cret")
        val second = CoworkerRabbitMqProperties.of("broker.example.nl", 5672, "/", "user", "s3cret")

        assertThat(provider.connectionFactory(first)).isSameAs(provider.connectionFactory(second))
        assertThat(provider.rabbitTemplate(first)).isSameAs(provider.rabbitTemplate(second))
    }

    @Test
    fun `different credentials get their own connection`() {
        val first = CoworkerRabbitMqProperties.of("broker.example.nl", 5672, "/", "user-a", "s3cret")
        val second = CoworkerRabbitMqProperties.of("broker.example.nl", 5672, "/", "user-b", "s3cret")

        assertThat(provider.connectionFactory(first)).isNotSameAs(provider.connectionFactory(second))
    }

    @Test
    fun `blank fields count as unset rather than as an override`() {
        val properties = CoworkerRabbitMqProperties.of("", null, "  ", "", null)

        assertThat(properties.overridesApplicationDefaults).isFalse()
        assertThat(provider.connectionFactory(properties)).isSameAs(applicationConnectionFactory)
    }

    @Test
    fun `TLS configured on the application is inherited when a configuration only sets credentials`() {
        // An app talking amqps:// — setting just a username must not drop back to plaintext.
        val secureApplicationFactory =
            CachingConnectionFactory(
                com.rabbitmq.client.ConnectionFactory().apply { useSslProtocol(SSLContext.getDefault()) },
            ).apply { setHost("secure-host") }
        val secureProvider = CoworkerConnectionFactoryProvider(secureApplicationFactory)

        try {
            val connectionFactory =
                secureProvider.connectionFactory(
                    CoworkerRabbitMqProperties.of(null, null, null, "coworker-user", "s3cret"),
                ) as CachingConnectionFactory

            assertThat(connectionFactory.rabbitConnectionFactory.isSSL).isTrue()
            assertThat(connectionFactory.username).isEqualTo("coworker-user")
        } finally {
            secureProvider.destroy()
            secureApplicationFactory.destroy()
        }
    }

    @Test
    fun `the port alone does not enable TLS`() {
        // 5671 is only a convention; the client decides on the socket factory.
        val connectionFactory =
            provider.connectionFactory(
                CoworkerRabbitMqProperties.of("broker.example.nl", 5671, null, null, null),
            ) as CachingConnectionFactory

        assertThat(connectionFactory.port).isEqualTo(5671)
        assertThat(connectionFactory.rabbitConnectionFactory.isSSL).isFalse()
    }

    @Test
    fun `enabling TLS explicitly switches the connection to amqps and defaults the port to 5671`() {
        val connectionFactory =
            provider.connectionFactory(
                CoworkerRabbitMqProperties.of("broker.example.nl", null, null, null, null, sslEnabled = true),
            ) as CachingConnectionFactory

        assertThat(connectionFactory.rabbitConnectionFactory.isSSL).isTrue()
        // No explicit port: the client derives the TLS default rather than falling back to 5672.
        assertThat(connectionFactory.rabbitConnectionFactory.port).isEqualTo(5671)
    }

    @Test
    fun `TLS can be turned off explicitly for a broker that does not use it`() {
        val secureApplicationFactory =
            CachingConnectionFactory(
                com.rabbitmq.client.ConnectionFactory().apply { useSslProtocol(SSLContext.getDefault()) },
            )
        val secureProvider = CoworkerConnectionFactoryProvider(secureApplicationFactory)

        try {
            val connectionFactory =
                secureProvider.connectionFactory(
                    CoworkerRabbitMqProperties.of("plain.example.nl", null, null, null, null, sslEnabled = false),
                ) as CachingConnectionFactory

            assertThat(connectionFactory.rabbitConnectionFactory.isSSL).isFalse()
        } finally {
            secureProvider.destroy()
            secureApplicationFactory.destroy()
        }
    }

    @Test
    fun `the TLS setting is part of the connection identity`() {
        val plain = CoworkerRabbitMqProperties.of("broker", null, null, "u", "p", sslEnabled = false)
        val secure = CoworkerRabbitMqProperties.of("broker", null, null, "u", "p", sslEnabled = true)

        assertThat(provider.connectionFactory(plain)).isNotSameAs(provider.connectionFactory(secure))
    }

    @Test
    fun `the password never appears in the properties toString`() {
        val properties = CoworkerRabbitMqProperties.of("broker", 5672, "/", "user", "hunter2")

        assertThat(properties.toString()).doesNotContain("hunter2")
        assertThat(properties.toString()).contains("user")
    }
}
