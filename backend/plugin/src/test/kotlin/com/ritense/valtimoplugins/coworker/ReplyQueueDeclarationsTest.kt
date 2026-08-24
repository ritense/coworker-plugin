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

import com.ritense.valtimoplugins.coworker.listener.ReplyQueueDeclarations
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.amqp.AmqpConnectException
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.Connection
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import java.util.Properties

class ReplyQueueDeclarationsTest : BaseTest() {
    private val connectionFactory = mock<ConnectionFactory>()
    private val admin = mock<RabbitAdmin>()
    private val declarations = ReplyQueueDeclarations(connectionFactory, admin)

    private fun declaredQueues(count: Int): List<Queue> =
        argumentCaptor<Queue>().apply { verify(admin, times(count)).declareQueue(capture()) }.allValues

    @Test
    fun `adding a queue declares it durably and not auto-delete`() {
        declarations.add("coworker-plugin.reply")

        val queue = declaredQueues(1).single()
        assertThat(queue.name).isEqualTo("coworker-plugin.reply")
        // A reply queue has to outlive both a gap in its consumers and a broker restart,
        // or replies sent in the meantime have nowhere to land.
        assertThat(queue.isDurable).isTrue()
        assertThat(queue.isAutoDelete).isFalse()
        assertThat(queue.isExclusive).isFalse()
    }

    @Test
    fun `every added queue is declared again on a new connection`() {
        // The regression this class exists for: a broker that comes back with empty state
        // has lost the queue, and the consumer's passive declare then fails with 404 for
        // the rest of the application's life unless something puts the queue back.
        declarations.add("first.reply")
        declarations.add("second.reply")

        declarations.onCreate(mock<Connection>())

        assertThat(declaredQueues(4).map { it.name })
            .containsExactlyInAnyOrder("first.reply", "second.reply", "first.reply", "second.reply")
    }

    @Test
    fun `a removed queue is no longer declared on a new connection`() {
        declarations.add("first.reply")
        declarations.add("second.reply")

        assertThat(declarations.remove("first.reply")).isFalse()

        declarations.onCreate(mock<Connection>())

        assertThat(declaredQueues(3).map { it.name })
            .containsExactly("first.reply", "second.reply", "second.reply")
    }

    @Test
    fun `removing the last queue reports that the connection serves nothing`() {
        declarations.add("only.reply")

        assertThat(declarations.remove("only.reply")).isTrue()
    }

    @Test
    fun `a queue that is gone from the broker is declared again`() {
        whenever(admin.getQueueProperties("coworker-plugin.reply")).thenReturn(null)

        declarations.redeclareIfMissing("coworker-plugin.reply")

        assertThat(declaredQueues(1).single().name).isEqualTo("coworker-plugin.reply")
    }

    @Test
    fun `a queue that is still on the broker is left alone`() {
        whenever(admin.getQueueProperties("coworker-plugin.reply")).thenReturn(Properties())

        declarations.redeclareIfMissing("coworker-plugin.reply")

        verify(admin, never()).declareQueue(any<Queue>())
    }

    @Test
    fun `an unreachable broker is not mistaken for a deleted queue`() {
        // getQueueProperties cannot tell "deleted" from "unreachable"; re-declaring over a
        // dead connection would only fail a second time and log a misleading warning.
        whenever(admin.getQueueProperties(any())).thenThrow(AmqpConnectException(RuntimeException("no route")))

        declarations.redeclareIfMissing("coworker-plugin.reply")

        verify(admin, never()).declareQueue(any<Queue>())
    }

    @Test
    fun `a broker that refuses the declaration does not fail the caller`() {
        // The user may hold no 'configure' permission on a queue that already exists,
        // which is not a reason to abandon the subscription.
        whenever(admin.declareQueue(any<Queue>())).thenThrow(AmqpConnectException(RuntimeException("denied")))

        declarations.add("coworker-plugin.reply")
        declarations.onCreate(mock<Connection>())
    }

    @Test
    fun `attaching and detaching registers and unregisters the connection listener`() {
        declarations.attach()
        verify(connectionFactory).addConnectionListener(declarations)

        declarations.detach()
        verify(connectionFactory).removeConnectionListener(declarations)
    }
}
