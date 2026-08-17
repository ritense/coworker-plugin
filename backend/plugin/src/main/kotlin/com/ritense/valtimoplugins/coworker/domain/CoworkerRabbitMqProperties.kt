
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

package com.ritense.valtimoplugins.coworker.domain

/**
 * The broker connection a CoWorker plugin configuration talks to. Every field is
 * optional: an unset field falls back to whatever the host app configured under
 * `spring.rabbitmq.*`, so a configuration that only fills in credentials keeps
 * using the application's host and port. An app that configures no broker of its
 * own has nothing to fall back to, so there a configuration has to describe its
 * broker in full — see `CoworkerConnectionFactoryProvider`.
 *
 * Doubles as the cache key for connection factories and reply-queue listeners
 * (see `CoworkerConnectionFactoryProvider` / `CoworkerReplyListenerManager`), so it
 * is a value type — two configurations pointing at the same broker with the same
 * credentials share one connection.
 */
data class CoworkerRabbitMqProperties(
    val host: String? = null,
    val port: Int? = null,
    val virtualHost: String? = null,
    val username: String? = null,
    val password: String? = null,
    /**
     * `true` forces TLS (amqps), `false` forces plain amqp, `null` inherits whatever the
     * host app is configured with. TLS is never implied by [port]: the RabbitMQ client
     * decides on the socket factory alone, and uses the TLS setting only to pick the
     * default port (5671 vs 5672).
     */
    val sslEnabled: Boolean? = null,
) {
    /**
     * Whether this configuration overrides the application's broker connection at
     * all. When it does not, the host app's own `ConnectionFactory` is reused
     * rather than a second one being opened alongside it.
     */
    val overridesApplicationDefaults: Boolean
        get() =
            port != null ||
                sslEnabled != null ||
                sequenceOf(host, virtualHost, username, password).any { !it.isNullOrBlank() }

    /** Never let the password reach a log line or an exception message. */
    override fun toString(): String =
        "CoworkerRabbitMqProperties(host=$host, port=$port, virtualHost=$virtualHost, " +
            "username=$username, password=${if (password.isNullOrBlank()) "null" else "***"}, " +
            "sslEnabled=$sslEnabled)"

    companion object {
        /** A configuration that adds nothing on top of `spring.rabbitmq.*`. */
        val APPLICATION_DEFAULTS = CoworkerRabbitMqProperties()

        /** Treats blank form fields as "not set", so an empty input is not sent to the broker. */
        fun of(
            host: String?,
            port: Int?,
            virtualHost: String?,
            username: String?,
            password: String?,
            sslEnabled: Boolean? = null,
        ): CoworkerRabbitMqProperties =
            CoworkerRabbitMqProperties(
                host = host?.takeIf { it.isNotBlank() },
                port = port,
                virtualHost = virtualHost?.takeIf { it.isNotBlank() },
                username = username?.takeIf { it.isNotBlank() },
                password = password?.takeIf { it.isNotBlank() },
                sslEnabled = sslEnabled,
            )
    }
}
