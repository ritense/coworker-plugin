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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.Connection
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.connection.ConnectionListener
import org.springframework.amqp.rabbit.core.RabbitAdmin
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the CoWorker reply queues of one broker connection declared, for as long as
 * something is listening to them.
 *
 * Declaring a queue once, when the listener starts, is not enough. A broker that comes
 * back with empty state — rebuilt, restarted without its data, or failed over — has lost
 * every queue, and nothing puts this one back. The consumer reconnects, its passive
 * declare fails with `404 NOT_FOUND`, and because the container is deliberately not
 * `missingQueuesFatal` it retries that same doomed declare every five seconds for the
 * rest of the application's life. Replies are then published to a queue that is not
 * there and are lost, and the only way out is to point the plugin configuration at a
 * different queue name — which works exactly once, until the next time.
 *
 * The queues the CoWorker server owns do not have this problem: they are
 * application-context beans, and Spring re-declares those on every new connection. A
 * reply queue is only known once a plugin configuration names it, so it can never be
 * such a bean. Registering as a [ConnectionListener] gives it the same guarantee by
 * hand — whatever this connection currently serves is re-declared each time it is
 * established.
 *
 * One instance is shared by every subscription pointed at the same broker, because the
 * connection factories are shared too.
 */
internal open class ReplyQueueDeclarations(
    private val connectionFactory: ConnectionFactory,
    private val admin: RabbitAdmin,
) : ConnectionListener {
    private val queues = ConcurrentHashMap.newKeySet<String>()

    fun attach() {
        connectionFactory.addConnectionListener(this)
    }

    fun detach() {
        connectionFactory.removeConnectionListener(this)
    }

    /** Declares [queue] now, and again on every connection from here on. */
    fun add(queue: String) {
        queues.add(queue)
        declare(queue)
    }

    /**
     * Stops keeping [queue] declared. Returns true when this connection has no reply
     * queues left at all, so the caller can drop the listener with it.
     */
    fun remove(queue: String): Boolean {
        queues.remove(queue)
        return queues.isEmpty()
    }

    /**
     * Puts [queue] back if the broker no longer has it — the case [onCreate] cannot see,
     * where the queue is deleted while the connection stays up. The container needs no
     * restart: its next retry finds the queue and attaches to it.
     *
     * A broker that cannot be reached at all is left alone. `getQueueProperties` cannot
     * tell "deleted" from "unreachable", and re-declaring over a dead connection would
     * only fail a second time.
     */
    fun redeclareIfMissing(queue: String) {
        val missing = runCatching { admin.getQueueProperties(queue) == null }.getOrDefault(false)
        if (missing) {
            logger.warn { "CoWorker reply queue '$queue' is no longer on the broker; re-declaring it" }
            declare(queue)
        }
    }

    /**
     * Safe to declare from here even though this runs inside `createConnection()`:
     * `CachingConnectionFactory` assigns the connection before notifying its listeners
     * and guards it with a `ReentrantLock`, so the declare re-enters and gets the
     * connection being established.
     */
    override fun onCreate(connection: Connection) {
        queues.forEach { declare(it) }
    }

    private fun declare(queue: String) {
        // Durable and not auto-delete: the queue has to outlive both this application and
        // any gap in its consumers, or replies sent in the meantime have nowhere to land.
        runCatching { admin.declareQueue(QueueBuilder.durable(queue).build()) }
            .onFailure {
                // WARN, not DEBUG: if the queue does not exist and cannot be declared, the
                // CoWorker server's replies are published to the default exchange with no
                // matching queue and are dropped without a trace.
                logger.warn(it) {
                    "Could not declare CoWorker reply queue '$queue'. If it does not already " +
                        "exist on the broker, replies will be silently discarded."
                }
            }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
