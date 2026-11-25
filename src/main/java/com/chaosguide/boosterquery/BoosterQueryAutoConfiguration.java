/*
 * Copyright 2025 ChaosGuide
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chaosguide.boosterquery;

import com.chaosguide.boosterquery.cache.BoosterCache;
import com.chaosguide.boosterquery.cache.CaffeineBoosterCache;
import com.chaosguide.boosterquery.config.BoosterQueryConfig;
import com.chaosguide.boosterquery.config.BoosterQueryProperties;
import com.chaosguide.boosterquery.executor.BoosterNativeExecutor;
import com.chaosguide.boosterquery.executor.BoosterQueryExecutor;
import com.chaosguide.boosterquery.support.MetricsRecorder;
import com.chaosguide.boosterquery.support.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Auto-configuration entry point for BoosterQuery.
 * <p>
 * When JPA infrastructure is present in the container, automatically registers:
 * - {@link BoosterNativeExecutor} - basic query executor
 * - {@link BoosterQueryConfig} - query rewriting configuration
 * - {@link BoosterQueryExecutor} - enhanced query executor
 * - {@link BoosterCache} - SQL transformation cache (if enabled)
 * <p>
 * Allows business applications or custom repositories to inject and use these beans
 * without additional configuration.
 */
@AutoConfiguration(
        after = {HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class},
        afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration")
@Conditional(BoosterQueryAutoConfiguration.JpaAvailableCondition.class)
@EnableConfigurationProperties(BoosterQueryProperties.class)
@Import(BoosterQueryAutoConfiguration.MicrometerConfiguration.class)
public class BoosterQueryAutoConfiguration {

    /**
     * Registers a basic {@link BoosterNativeExecutor} bean backed by the given {@link EntityManager}.
     * <p>
     * Only created when no other {@code BoosterNativeExecutor} bean exists in the context.
     *
     * @param entityManager the JPA entity manager
     * @return a new basic query executor instance
     */
    public BoosterNativeExecutor boosterNativeExecutor(EntityManager entityManager) {
        return new BoosterNativeExecutor(entityManager);
    }

    @Bean("boosterNativeExecutor")
    @ConditionalOnMissingBean(BoosterNativeExecutor.class)
    BoosterNativeExecutor configuredBoosterNativeExecutor(ObjectProvider<EntityManager> entityManagers,
                                                         ObjectProvider<EntityManagerFactory> factories) {
        return boosterNativeExecutor(resolveEntityManager(entityManagers, factories));
    }

    /**
     * Creates a {@link BoosterQueryConfig} from externalized {@link BoosterQueryProperties}.
     * <p>
     * Transfers {@code defaultLimit}, {@code enableAutoLimit}, and {@code enableSqlRewrite}
     * settings from the bound properties into a mutable config object.
     *
     * @param properties the externalized configuration properties (prefix {@code booster.query})
     * @return a new rewriter configuration instance
     */
    @Bean
    @ConditionalOnMissingBean
    public BoosterQueryConfig boosterQueryConfig(BoosterQueryProperties properties) {
        BoosterQueryConfig config = new BoosterQueryConfig();
        config.setDefaultLimit(properties.getDefaultLimit());
        config.setEnableAutoLimit(properties.isEnableAutoLimit());
        config.setEnableSqlRewrite(properties.isEnableSqlRewrite());
        return config;
    }

    /**
     * Registers a Caffeine-based {@link BoosterCache} when caching is explicitly enabled
     * via {@code booster.query.cache.enabled=true}.
     * <p>
     * Cache size and TTL are controlled by {@link BoosterQueryProperties.Cache}.
     *
     * @param properties the externalized configuration properties containing cache settings
     * @return a new {@link CaffeineBoosterCache} instance
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "booster.query.cache", name = "enabled", havingValue = "true")
    public BoosterCache boosterCache(BoosterQueryProperties properties) {
        return new CaffeineBoosterCache(properties.getCache());
    }

    /**
     * Registers a {@link BoosterQueryExecutor} that wraps the basic executor with SQL
     * rewriting, auto-limit, and optional caching.
     * <p>
     * The {@link BoosterCache} dependency is resolved lazily via {@link ObjectProvider} and
     * may be {@code null} when caching is not enabled.
     * <p>
     * {@link MetricsRecorder} is optionally resolved from the container. When Micrometer or a
     * {@link MeterRegistry} bean is not available, no {@code MetricsRecorder} bean exists and the
     * executor defaults to no-op metrics.
     *
     * @param entityManager   the JPA entity manager
     * @param config          the SQL rewriter configuration
     * @param cacheProvider   lazy provider for the optional cache bean
     * @param metricsProvider lazy provider for the optional metrics recorder bean
     * @return a new enhanced query executor instance
     */
    public BoosterQueryExecutor boosterQueryExecutor(EntityManager entityManager,
                                                       BoosterQueryConfig config,
                                                       ObjectProvider<BoosterCache> cacheProvider,
                                                       ObjectProvider<MetricsRecorder> metricsProvider) {
        return new BoosterQueryExecutor(entityManager, config, cacheProvider.getIfAvailable(), metricsProvider.getIfAvailable());
    }

    @Bean("boosterQueryExecutor")
    @ConditionalOnMissingBean(BoosterQueryExecutor.class)
    BoosterQueryExecutor configuredBoosterQueryExecutor(ObjectProvider<EntityManager> entityManagers,
                                                       ObjectProvider<EntityManagerFactory> factories,
                                                       BoosterQueryConfig config,
                                                       ObjectProvider<BoosterCache> cacheProvider,
                                                       ObjectProvider<MetricsRecorder> metricsProvider) {
        return boosterQueryExecutor(resolveEntityManager(entityManagers, factories), config, cacheProvider, metricsProvider);
    }

    private static EntityManager resolveEntityManager(ObjectProvider<EntityManager> entityManagers,
                                                      ObjectProvider<EntityManagerFactory> factories) {
        EntityManager entityManager = entityManagers.getIfAvailable();
        return entityManager != null
                ? entityManager
                : SharedEntityManagerCreator.createSharedEntityManager(factories.getObject());
    }

    // Spring Data can register shared EntityManager proxies after condition evaluation.
    // EntityManagerFactory is already visible at this phase in a normal JPA application.
    static class JpaAvailableCondition extends AnyNestedCondition {
        JpaAvailableCondition() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnBean(EntityManager.class)
        static class EntityManagerAvailable {}

        @ConditionalOnBean(EntityManagerFactory.class)
        static class EntityManagerFactoryAvailable {}
    }

    /**
     * Inner conditional configuration class: only loaded when Micrometer and a single candidate
     * {@link MeterRegistry} bean are available.
     * <p>
     * Registers a {@link MicrometerMetricsRecorder} bean backed by the application's
     * {@link MeterRegistry}.
     */
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnSingleCandidate(MeterRegistry.class)
    static class MicrometerConfiguration {

        @Bean
        @ConditionalOnMissingBean(MetricsRecorder.class)
        MetricsRecorder boosterMetricsRecorder(MeterRegistry registry) {
            return new MicrometerMetricsRecorder(registry);
        }
    }
}
