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

package com.ritense.valtimoplugins.coworker.listener

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginConfiguration
import com.ritense.plugin.events.PluginConfigurationDeletedEvent
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.valtimoplugins.coworker.domain.CoworkerRabbitMqProperties
import com.ritense.valtimoplugins.coworker.plugin.CoworkerPlugin
import com.ritense.valtimoplugins.coworker.transport.CoworkerConnectionFactoryProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.core.MessageListener
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs one RabbitMQ listener per distinct (reply queue, broker connection) found
 * across the CoWorker plugin configurations, and keeps that set in step as
 * configurations are added, changed or removed.
 *
 * This replaces a static `@RabbitListener`, which could only ever bind to the host
 * app's own connection: the queue name and the credentials now come from a plugin
 * configuration, and those are not known until one has been saved.
 *
 * [synchronize] is the only entry point and is idempotent — it computes the desired
 * set of subscriptions and starts or stops containers to match. It is called on
 * startup, whenever a configuration is saved (via `CoworkerPlugin`'s `@PluginEvent`,
 * which also covers autodeployment) or deleted, and periodically as a safety net for
 * anything those hooks miss (a rolled-back transaction, or a configuration created by
 * another node).
 */
open class CoworkerReplyListenerManager(
    private val pluginConfigurationRepository: PluginConfigurationRepository,
    private val connectionFactoryProvider: CoworkerConnectionFactoryProvider,
    private val replyListener: CoworkerReplyListener,
    private val listenersEnabled: Boolean,
) : DisposableBean {
    private val containers = ConcurrentHashMap<ReplySubscription, SimpleMessageListenerContainer>()
    private val lock = ReentrantLock()
    private val disabledWarningLogged = AtomicBoolean(false)

    /** The queue to consume and the broker to consume it from. */
    internal data class ReplySubscription(
        val replyQueue: String,
        val connection: CoworkerRabbitMqProperties,
    )

    @EventListener(ApplicationReadyEvent::class)
    open fun onApplicationReady() = synchronize()

    /**
     * Deletion is picked up here rather than through a `@PluginEvent`, because the
     * plugin's DELETE event runs *before* the row is gone — a synchronize at that
     * moment would still see the configuration it is meant to drop.
     */
    @EventListener(PluginConfigurationDeletedEvent::class)
    open fun onPluginConfigurationDeleted() = synchronize()

    /**
     * Safety net for changes the hooks above cannot see: a save that was rolled back
     * after its plugin event ran, or a configuration created on another node.
     */
    @Scheduled(
        initialDelayString = "\${valtimo.coworker.listener-refresh-interval:PT5M}",
        fixedDelayString = "\${valtimo.coworker.listener-refresh-interval:PT5M}",
    )
    open fun refreshPeriodically() = synchronize()

    /**
     * Brings the running listeners in line with the stored plugin configurations.
     *
     * Deliberately not `@Transactional`: two of the three callers above are
     * self-invocations, which would bypass the proxy and make the annotation apply only
     * some of the time. None is needed — `PluginConfiguration.pluginDefinition` and
     * `PluginDefinition.properties` are both fetched eagerly, so decrypting the stored
     * properties does not touch a lazy association.
     */
    open fun synchronize() {
        if (!listenersEnabled) {
            // Announced once at INFO — "no replies are being consumed" is worth seeing when
            // replies do not arrive — then quietly, since the reconcile repeats every few
            // minutes for the lifetime of the application.
            if (disabledWarningLogged.compareAndSet(false, true)) {
                logger.info {
                    "CoWorker reply listeners are disabled " +
                        "(spring.rabbitmq.listener.simple.auto-startup=false); no replies will be received"
                }
            }
            return
        }

        val configurations = pluginConfigurationRepository.findByPluginDefinitionKey(CoworkerPlugin.PLUGIN_KEY)
        val desired = configurations.mapNotNull { subscriptionOf(it) }.toSet()

        // Answers "is anything listening, and to what?" in one line. At INFO only when the
        // set actually changes; the periodic reconcile is otherwise silent.
        val summary = {
            "CoWorker reply listeners: ${configurations.size} plugin configuration(s), " +
                "${desired.size} distinct reply queue(s) ${desired.map { it.replyQueue }}, " +
                "${containers.size} currently running ${containers.keys.map { it.replyQueue }}"
        }
        if (desired == containers.keys) logger.debug(summary) else logger.info(summary)

        lock.withLock {
            (containers.keys - desired).forEach { stop(it) }
            (desired - containers.keys).forEach { subscription ->
                // One unreachable broker must not stop the others from being attached,
                // and must not propagate: a throw here would abort the plugin save that
                // triggered it (see CoworkerPlugin.onConfigurationSaved). A subscription
                // that fails simply stays absent and is retried on the next reconcile.
                runCatching { start(subscription) }
                    .onFailure {
                        logger.error(it) {
                            "Could not start the CoWorker reply listener on " +
                                "'${subscription.replyQueue}' (${subscription.connection}); will retry"
                        }
                    }
            }
        }
    }

    internal fun subscriptionOf(configuration: PluginConfiguration): ReplySubscription? {
        // Read the stored properties directly rather than going through
        // PluginService.createInstance: that would need the PluginService, which in turn
        // needs the plugin factory, which needs this manager. The getter decrypts the
        // password on the way out.
        val properties: ObjectNode = configuration.properties ?: return null

        val replyQueue = properties.textOrNull(REPLY_QUEUE)
        if (replyQueue == null) {
            logger.warn {
                "CoWorker plugin configuration '${configuration.title}' has no replyQueue; not listening for its replies"
            }
            return null
        }

        return ReplySubscription(
            replyQueue = replyQueue,
            connection =
                CoworkerRabbitMqProperties.of(
                    host = properties.textOrNull(RABBITMQ_HOST),
                    port = properties.get(RABBITMQ_PORT)?.takeIf { it.isNumber || it.isTextual }?.asInt()?.takeIf { it > 0 },
                    virtualHost = properties.textOrNull(RABBITMQ_VIRTUAL_HOST),
                    username = properties.textOrNull(RABBITMQ_USERNAME),
                    password = properties.textOrNull(RABBITMQ_PASSWORD),
                    sslEnabled = properties.get(RABBITMQ_SSL_ENABLED)?.takeIf { it.isBoolean }?.asBoolean(),
                ),
        )
    }

    private fun ObjectNode.textOrNull(field: String): String? =
        get(field)?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }

    private fun start(subscription: ReplySubscription) {
        val connectionFactory = connectionFactoryProvider.connectionFactory(subscription.connection)

        // Declare the queue durably on this configuration's own connection. Failing to do
        // so is not fatal: the broker may simply not grant this user 'configure' rights on
        // an already-existing queue.
        runCatching {
            RabbitAdmin(connectionFactory).declareQueue(QueueBuilder.durable(subscription.replyQueue).build())
        }.onFailure {
            // WARN, not DEBUG: if the queue does not exist and cannot be declared, the
            // CoWorker server's replies are published to the default exchange with no
            // matching queue and are dropped without a trace.
            logger.warn(it) {
                "Could not declare CoWorker reply queue '${subscription.replyQueue}'. If it does not already " +
                    "exist on the broker, replies will be silently discarded."
            }
        }

        val container =
            SimpleMessageListenerContainer(connectionFactory).apply {
                setQueueNames(subscription.replyQueue)
                // The listener bean is called through its Spring proxy, so its
                // @Transactional(REQUIRES_NEW) and @RunWithoutAuthorization still apply.
                setMessageListener(MessageListener { replyListener.onReply(it) })
                // A queue that is not there yet must not kill the container; the broker may
                // still be starting, or the CoWorker server may declare the queue itself.
                setMissingQueuesFatal(false)
                setBeanName("coworker-reply-${subscription.replyQueue}")
            }

        try {
            container.start()
        } catch (e: Exception) {
            // Do not leave a half-started container behind holding consumer threads;
            // the subscription stays unregistered and is retried on the next reconcile.
            runCatching { container.destroy() }
            throw e
        }

        containers[subscription] = container
        logger.info {
            "Listening for CoWorker replies on '${subscription.replyQueue}' (${subscription.connection})"
        }
    }

    private fun stop(subscription: ReplySubscription) {
        containers.remove(subscription)?.let { container ->
            runCatching { container.destroy() }
                .onFailure { logger.warn(it) { "Failed to stop CoWorker reply listener on '${subscription.replyQueue}'" } }
            logger.info { "Stopped listening for CoWorker replies on '${subscription.replyQueue}'" }
        }
    }

    override fun destroy() {
        lock.withLock { containers.keys.toList().forEach { stop(it) } }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private const val REPLY_QUEUE = "replyQueue"
        private const val RABBITMQ_HOST = "rabbitMqHost"
        private const val RABBITMQ_PORT = "rabbitMqPort"
        private const val RABBITMQ_VIRTUAL_HOST = "rabbitMqVirtualHost"
        private const val RABBITMQ_USERNAME = "rabbitMqUsername"
        private const val RABBITMQ_PASSWORD = "rabbitMqPassword"
        private const val RABBITMQ_SSL_ENABLED = "rabbitMqSslEnabled"
    }
}
