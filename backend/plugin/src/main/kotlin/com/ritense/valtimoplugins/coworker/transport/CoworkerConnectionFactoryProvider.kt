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

package com.ritense.valtimoplugins.coworker.transport

import com.ritense.valtimoplugins.coworker.domain.CoworkerRabbitMqProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.SimpleMessageConverter
import org.springframework.beans.factory.DisposableBean
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory
import javax.net.ssl.SSLContext

/**
 * Hands out the broker connection belonging to a CoWorker plugin configuration.
 *
 * A configuration that leaves the RabbitMQ fields empty reuses the host app's own
 * `ConnectionFactory` (`spring.rabbitmq.*`) — the behaviour this plugin had before
 * per-configuration credentials existed. A configuration that fills any of them in
 * gets its own [CachingConnectionFactory], seeded from the application factory so
 * that setting only a username and password keeps the app's host and port.
 *
 * Connections are cached per distinct [CoworkerRabbitMqProperties], so several
 * plugin configurations aimed at the same broker with the same credentials share
 * one connection instead of opening one each.
 */
open class CoworkerConnectionFactoryProvider(
    private val applicationConnectionFactory: ConnectionFactory,
) : DisposableBean {
    private val connectionFactories = ConcurrentHashMap<CoworkerRabbitMqProperties, CachingConnectionFactory>()
    private val rabbitTemplates = ConcurrentHashMap<CoworkerRabbitMqProperties, RabbitTemplate>()

    open fun connectionFactory(properties: CoworkerRabbitMqProperties): ConnectionFactory =
        if (!properties.overridesApplicationDefaults) {
            applicationConnectionFactory
        } else {
            connectionFactories.computeIfAbsent(properties) { create(it) }
        }

    /**
     * The publishing template for [properties]. Uses [SimpleMessageConverter] so the
     * already-serialized CloudEvent JSON is sent through unchanged rather than being
     * encoded a second time.
     */
    open fun rabbitTemplate(properties: CoworkerRabbitMqProperties): RabbitTemplate =
        rabbitTemplates.computeIfAbsent(properties) {
            RabbitTemplate(connectionFactory(it)).apply { messageConverter = SimpleMessageConverter() }
        }

    private fun create(properties: CoworkerRabbitMqProperties): CachingConnectionFactory {
        // Start from a copy of the connection the host app already built, so everything
        // that is not a plugin property — most importantly the TLS setup from
        // `spring.rabbitmq.ssl.*` / an `amqps://` address, but also timeouts and SASL
        // config — is inherited instead of silently reset to the client's defaults.
        // ConnectionFactory.clone() is a shallow copy, which is what we want: the
        // SSLSocketFactory is shared rather than rebuilt.
        val rabbitConnectionFactory =
            (applicationConnectionFactory as? AbstractConnectionFactory)
                ?.rabbitConnectionFactory
                ?.clone()
                ?: com.rabbitmq.client.ConnectionFactory()

        properties.sslEnabled?.let { sslEnabled ->
            enableTls(rabbitConnectionFactory, sslEnabled)
            // Switching transport without naming a port would otherwise keep the port
            // inherited from the app (typically an explicit 5672) and dial TLS at a
            // plaintext listener. Hand the choice back to the client, which resolves
            // USE_DEFAULT_PORT to 5671 for TLS and 5672 for plain amqp.
            if (properties.port == null) {
                rabbitConnectionFactory.port = com.rabbitmq.client.ConnectionFactory.USE_DEFAULT_PORT
            }
        }

        val connectionFactory = CachingConnectionFactory(rabbitConnectionFactory)
        connectionFactory.setHost(properties.host ?: applicationConnectionFactory.host)
        properties.port?.let { connectionFactory.port = it }
        (properties.virtualHost ?: applicationConnectionFactory.virtualHost)?.let {
            connectionFactory.setVirtualHost(it)
        }
        (properties.username ?: applicationConnectionFactory.username)?.let {
            connectionFactory.setUsername(it)
        }
        // Only the plugin configuration can supply a password; Spring's ConnectionFactory
        // interface deliberately does not expose the one it was built with. A configuration
        // that sets a username therefore has to set the matching password too.
        properties.password?.let { connectionFactory.setPassword(it) }

        logger.info {
            "Opened a dedicated CoWorker RabbitMQ connection factory for " +
                "${connectionFactory.host}:${connectionFactory.port} (vhost '${connectionFactory.virtualHost}', " +
                "user '${connectionFactory.username}', tls=${rabbitConnectionFactory.isSSL})"
        }
        return connectionFactory
    }

    /**
     * Turns TLS on or off explicitly, for a broker whose transport differs from the host
     * app's. TLS is never implied by the port: the client decides purely on whether a
     * `SSLSocketFactory` is set (`ConnectionFactory.isSSL()`), and uses the TLS setting to
     * pick the *default* port, not the other way round.
     *
     * Uses the JVM's default `SSLContext` — so the platform trust store applies and server
     * certificates are actually validated — with hostname verification on. Deliberately not
     * the no-argument `useSslProtocol()`, which trusts every certificate presented and is
     * documented as suitable for development only.
     */
    private fun enableTls(
        rabbitConnectionFactory: com.rabbitmq.client.ConnectionFactory,
        enabled: Boolean,
    ) {
        if (!enabled) {
            rabbitConnectionFactory.setSocketFactory(SocketFactory.getDefault())
            rabbitConnectionFactory.setSslContextFactory(null)
            return
        }
        rabbitConnectionFactory.useSslProtocol(SSLContext.getDefault())
        rabbitConnectionFactory.enableHostnameVerification()
    }

    override fun destroy() {
        rabbitTemplates.clear()
        connectionFactories.values.forEach { factory ->
            runCatching { factory.destroy() }
                .onFailure { logger.warn(it) { "Failed to close a CoWorker RabbitMQ connection factory" } }
        }
        connectionFactories.clear()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
