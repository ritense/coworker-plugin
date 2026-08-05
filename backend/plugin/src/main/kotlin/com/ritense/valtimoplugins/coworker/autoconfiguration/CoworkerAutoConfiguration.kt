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

package com.ritense.valtimoplugins.coworker.autoconfiguration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.service.PluginService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimo.contract.config.LiquibaseMasterChangeLogLocation
import com.ritense.valtimo.contract.document.CaseDocumentResolver
import com.ritense.valtimoplugins.coworker.domain.ProcessedCoworker
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListener
import com.ritense.valtimoplugins.coworker.plugin.CoworkerPluginFactory
import com.ritense.valtimoplugins.coworker.repository.CoworkerFailedEventRepository
import com.ritense.valtimoplugins.coworker.repository.ProcessedCoworkerRepository
import com.ritense.valtimoplugins.coworker.security.CoworkerHttpSecurityConfigurer
import com.ritense.valtimoplugins.coworker.service.CoworkerFailedEventRetryService
import com.ritense.valtimoplugins.coworker.service.CoworkerManagementService
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseProcessor
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import com.ritense.valtimoplugins.coworker.web.rest.CoworkerManagementResource
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.support.converter.SimpleMessageConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import org.springframework.core.annotation.Order
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.client.RestClient

@AutoConfiguration
@EnableScheduling
@EnableJpaRepositories(basePackageClasses = [ProcessedCoworkerRepository::class])
@EntityScan(basePackageClasses = [ProcessedCoworker::class])
class CoworkerAutoConfiguration {
    @Bean("coworkerRabbitTemplate")
    @ConditionalOnMissingBean(name = ["coworkerRabbitTemplate"])
    fun coworkerRabbitTemplate(connectionFactory: ConnectionFactory): RabbitTemplate =
        RabbitTemplate(connectionFactory).apply {
            // SimpleMessageConverter keeps our already-serialized JSON bytes intact
            // (no double-encoding); mirrors coworker-client's RabbitMqConfig.
            messageConverter = SimpleMessageConverter()
        }

    @Bean("coworkerReplyQueue")
    @ConditionalOnMissingBean(name = ["coworkerReplyQueue"])
    fun coworkerReplyQueue(
        @Value("\${valtimo.coworker.reply-queue:coworker-plugin.reply}") replyQueue: String,
    ): Queue = QueueBuilder.durable(replyQueue).build()

    @Bean
    @ConditionalOnMissingBean(RestCoworkerChatClient::class)
    fun restCoworkerChatClient(restClientBuilder: RestClient.Builder): RestCoworkerChatClient =
        RestCoworkerChatClient(restClientBuilder)

    @Bean
    @ConditionalOnMissingBean(RabbitMqCoworkerChatClient::class)
    fun rabbitMqCoworkerChatClient(
        @Qualifier("coworkerRabbitTemplate") coworkerRabbitTemplate: RabbitTemplate,
        objectMapper: ObjectMapper,
    ): RabbitMqCoworkerChatClient = RabbitMqCoworkerChatClient(coworkerRabbitTemplate, objectMapper)

    @Bean
    @ConditionalOnMissingBean(CoworkerPluginFactory::class)
    fun coworkerPluginFactory(
        pluginService: PluginService,
        restCoworkerChatClient: RestCoworkerChatClient,
        rabbitMqCoworkerChatClient: RabbitMqCoworkerChatClient,
        caseDocumentResolver: CaseDocumentResolver,
        objectMapper: ObjectMapper,
    ): CoworkerPluginFactory =
        CoworkerPluginFactory(
            pluginService,
            restCoworkerChatClient,
            rabbitMqCoworkerChatClient,
            caseDocumentResolver,
            objectMapper,
        )

    @Bean
    @ConditionalOnMissingBean(CoworkerManagementService::class)
    fun coworkerManagementService(
        pluginService: PluginService,
        restCoworkerChatClient: RestCoworkerChatClient,
    ): CoworkerManagementService = CoworkerManagementService(pluginService, restCoworkerChatClient)

    @Bean
    @ConditionalOnMissingBean(CoworkerManagementResource::class)
    fun coworkerManagementResource(coworkerManagementService: CoworkerManagementService): CoworkerManagementResource =
        CoworkerManagementResource(coworkerManagementService)

    @Order(391)
    @Bean
    @ConditionalOnMissingBean(CoworkerHttpSecurityConfigurer::class)
    fun coworkerHttpSecurityConfigurer(): CoworkerHttpSecurityConfigurer = CoworkerHttpSecurityConfigurer()

    @Bean
    @ConditionalOnMissingBean(CoworkerProcessResumeService::class)
    fun coworkerProcessResumeService(
        pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
        runtimeService: RuntimeService,
        repositoryService: RepositoryService,
        objectMapper: ObjectMapper,
    ): CoworkerProcessResumeService =
        CoworkerProcessResumeService(
            pluginProcessLinkRepository,
            runtimeService,
            repositoryService,
            objectMapper,
        )

    @Bean
    @ConditionalOnMissingBean(CoworkerResponseProcessor::class)
    fun coworkerResponseProcessor(
        objectMapper: ObjectMapper,
        processedCoworkerRepository: ProcessedCoworkerRepository,
        resumeService: CoworkerProcessResumeService,
    ): CoworkerResponseProcessor =
        CoworkerResponseProcessor(
            objectMapper,
            processedCoworkerRepository,
            resumeService,
        )

    @Bean
    @ConditionalOnMissingBean(CoworkerReplyListener::class)
    fun coworkerReplyListener(
        processor: CoworkerResponseProcessor,
        failedEventRepository: CoworkerFailedEventRepository,
    ): CoworkerReplyListener =
        CoworkerReplyListener(
            processor,
            failedEventRepository,
        )

    @Bean
    @ConditionalOnMissingBean(CoworkerFailedEventRetryService::class)
    fun coworkerFailedEventRetryService(
        failedEventRepository: CoworkerFailedEventRepository,
        processor: CoworkerResponseProcessor,
    ): CoworkerFailedEventRetryService =
        CoworkerFailedEventRetryService(
            failedEventRepository,
            processor,
        )

    @Order(HIGHEST_PRECEDENCE + 34)
    @Bean
    @ConditionalOnMissingBean(name = ["coworkerLiquibaseMasterChangeLogLocation"])
    fun coworkerLiquibaseMasterChangeLogLocation(): LiquibaseMasterChangeLogLocation =
        LiquibaseMasterChangeLogLocation("config/liquibase/coworker-master.xml")
}
