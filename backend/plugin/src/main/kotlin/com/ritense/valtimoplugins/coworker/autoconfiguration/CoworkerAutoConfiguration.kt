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
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.plugin.service.PluginService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.config.LiquibaseMasterChangeLogLocation
import com.ritense.valtimo.contract.document.CaseDocumentResolver
import com.ritense.valtimoplugins.coworker.domain.ProcessedCoworker
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListener
import com.ritense.valtimoplugins.coworker.listener.CoworkerReplyListenerManager
import com.ritense.valtimoplugins.coworker.plugin.CoworkerPluginFactory
import com.ritense.valtimoplugins.coworker.repository.CoworkerFailedEventRepository
import com.ritense.valtimoplugins.coworker.repository.ProcessedCoworkerRepository
import com.ritense.valtimoplugins.coworker.security.CoworkerHttpSecurityConfigurer
import com.ritense.valtimoplugins.coworker.service.CoworkerDocumentResolver
import com.ritense.valtimoplugins.coworker.service.CoworkerFailedEventRetryService
import com.ritense.valtimoplugins.coworker.service.CoworkerManagementService
import com.ritense.valtimoplugins.coworker.service.CoworkerProcessResumeService
import com.ritense.valtimoplugins.coworker.service.CoworkerResponseProcessor
import com.ritense.valtimoplugins.coworker.service.CoworkerResultMapper
import com.ritense.valtimoplugins.coworker.service.PromptTemplateResolver
import com.ritense.valtimoplugins.coworker.transport.CoworkerConnectionFactoryProvider
import com.ritense.valtimoplugins.coworker.transport.RabbitMqCoworkerChatClient
import com.ritense.valtimoplugins.coworker.transport.RestCoworkerChatClient
import com.ritense.valtimoplugins.coworker.web.rest.CoworkerManagementResource
import com.ritense.valueresolver.ValueResolverService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import org.springframework.core.annotation.Order
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.util.unit.DataSize
import org.springframework.web.client.RestClient

@AutoConfiguration
@EnableScheduling
@EnableJpaRepositories(basePackageClasses = [ProcessedCoworkerRepository::class])
@EntityScan(basePackageClasses = [ProcessedCoworker::class])
class CoworkerAutoConfiguration {
    // The reply queue is no longer declared from a static application property: which
    // queue to declare and consume now comes from each plugin configuration's
    // `replyQueue`, and CoworkerReplyListenerManager declares it durably on that
    // configuration's own connection when it starts listening.

    /**
     * The application's own broker connection is optional. A CoWorker plugin configuration
     * is meant to be set up entirely through the Valtimo web interface, so an app that has
     * no `spring.rabbitmq.*` — and therefore no `ConnectionFactory` bean — must still start;
     * configurations that name their own broker work regardless.
     */
    @Bean
    @ConditionalOnMissingBean(CoworkerConnectionFactoryProvider::class)
    fun coworkerConnectionFactoryProvider(
        connectionFactory: ObjectProvider<ConnectionFactory>,
    ): CoworkerConnectionFactoryProvider = CoworkerConnectionFactoryProvider(connectionFactory.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean(RestCoworkerChatClient::class)
    fun restCoworkerChatClient(restClientBuilder: RestClient.Builder): RestCoworkerChatClient =
        RestCoworkerChatClient(restClientBuilder)

    @Bean
    @ConditionalOnMissingBean(RabbitMqCoworkerChatClient::class)
    fun rabbitMqCoworkerChatClient(
        coworkerConnectionFactoryProvider: CoworkerConnectionFactoryProvider,
        objectMapper: ObjectMapper,
    ): RabbitMqCoworkerChatClient = RabbitMqCoworkerChatClient(coworkerConnectionFactoryProvider, objectMapper)

    @Bean
    @ConditionalOnMissingBean(PromptTemplateResolver::class)
    fun promptTemplateResolver(
        valueResolverService: ValueResolverService,
        objectMapper: ObjectMapper,
    ): PromptTemplateResolver = PromptTemplateResolver(valueResolverService, objectMapper)

    @Bean
    @ConditionalOnMissingBean(CoworkerDocumentResolver::class)
    fun coworkerDocumentResolver(
        temporaryResourceStorageService: TemporaryResourceStorageService,
        @Value("\${valtimo.coworker.max-document-size:10MB}") maxDocumentSize: DataSize,
    ): CoworkerDocumentResolver = CoworkerDocumentResolver(temporaryResourceStorageService, maxDocumentSize.toBytes())

    @Bean
    @ConditionalOnMissingBean(CoworkerPluginFactory::class)
    fun coworkerPluginFactory(
        pluginService: PluginService,
        restCoworkerChatClient: RestCoworkerChatClient,
        rabbitMqCoworkerChatClient: RabbitMqCoworkerChatClient,
        caseDocumentResolver: CaseDocumentResolver,
        promptTemplateResolver: PromptTemplateResolver,
        coworkerDocumentResolver: CoworkerDocumentResolver,
        coworkerReplyListenerManager: CoworkerReplyListenerManager,
        objectMapper: ObjectMapper,
    ): CoworkerPluginFactory =
        CoworkerPluginFactory(
            pluginService,
            restCoworkerChatClient,
            rabbitMqCoworkerChatClient,
            caseDocumentResolver,
            promptTemplateResolver,
            coworkerDocumentResolver,
            coworkerReplyListenerManager,
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
    @ConditionalOnMissingBean(CoworkerResultMapper::class)
    fun coworkerResultMapper(objectMapper: ObjectMapper): CoworkerResultMapper = CoworkerResultMapper(objectMapper)

    @Bean
    @ConditionalOnMissingBean(CoworkerProcessResumeService::class)
    fun coworkerProcessResumeService(
        pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
        runtimeService: RuntimeService,
        repositoryService: RepositoryService,
        coworkerResultMapper: CoworkerResultMapper,
        valueResolverService: ValueResolverService,
        objectMapper: ObjectMapper,
    ): CoworkerProcessResumeService =
        CoworkerProcessResumeService(
            pluginProcessLinkRepository,
            runtimeService,
            repositoryService,
            coworkerResultMapper,
            valueResolverService,
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
    @ConditionalOnMissingBean(CoworkerReplyListenerManager::class)
    fun coworkerReplyListenerManager(
        pluginConfigurationRepository: PluginConfigurationRepository,
        coworkerConnectionFactoryProvider: CoworkerConnectionFactoryProvider,
        coworkerReplyListener: CoworkerReplyListener,
        // Honours the standard Spring AMQP switch, so a deployment without a broker
        // (the integration-test harness, for one) starts no reply listeners at all.
        @Value("\${spring.rabbitmq.listener.simple.auto-startup:true}") listenersEnabled: Boolean,
    ): CoworkerReplyListenerManager =
        CoworkerReplyListenerManager(
            pluginConfigurationRepository,
            coworkerConnectionFactoryProvider,
            coworkerReplyListener,
            listenersEnabled,
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
