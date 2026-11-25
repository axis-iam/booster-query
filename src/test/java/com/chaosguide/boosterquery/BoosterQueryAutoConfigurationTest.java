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

import com.chaosguide.boosterquery.support.MetricsRecorder;
import com.chaosguide.boosterquery.support.MicrometerMetricsRecorder;
import com.chaosguide.boosterquery.executor.BoosterNativeExecutor;
import com.chaosguide.boosterquery.executor.BoosterQueryExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BoosterQueryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoosterQueryAutoConfiguration.class))
            .withBean(EntityManager.class, () -> mock(EntityManager.class));

    @Test
    void shouldBackOff_whenJpaInfrastructureIsMissing() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoosterQueryAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BoosterNativeExecutor.class);
                    assertThat(context).doesNotHaveBean(BoosterQueryExecutor.class);
                });
    }

    @Test
    void shouldCreateLazySharedExecutors_whenOnlyEntityManagerFactoryIsVisible() {
        EntityManagerFactory factory = mock(EntityManagerFactory.class);

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoosterQueryAutoConfiguration.class))
                .withBean(EntityManagerFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context).hasSingleBean(BoosterNativeExecutor.class);
                    assertThat(context).hasSingleBean(BoosterQueryExecutor.class);
                    assertThat(context).doesNotHaveBean(EntityManager.class);
                    verify(factory, never()).createEntityManager();
                });
    }

    @Test
    void shouldCreateRecorderAfterBootAutoConfiguresMeterRegistry() {
        contextRunner.withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(MeterRegistry.class);
                    assertThat(context).hasSingleBean(MicrometerMetricsRecorder.class);
                });
    }

    @Test
    void shouldNotRegisterMetricsRecorder_whenMeterRegistryIsMissing() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(MetricsRecorder.class));
    }

    @Test
    void shouldNotLoadMicrometerConfiguration_whenMicrometerIsMissingFromClasspath() {
        contextRunner.withClassLoader(new FilteredClassLoader(MeterRegistry.class))
                .run(context -> assertThat(context).doesNotHaveBean(MetricsRecorder.class));
    }

    @Test
    void shouldRegisterMicrometerMetricsRecorder_whenMeterRegistryIsAvailable() {
        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(MetricsRecorder.class);
                    assertThat(context).hasSingleBean(MicrometerMetricsRecorder.class);
                });
    }

    @Test
    void shouldNotRegisterMetricsRecorder_whenMultipleMeterRegistriesHaveNoPrimaryCandidate() {
        contextRunner.withBean("firstMeterRegistry", MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean("secondMeterRegistry", MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> assertThat(context).doesNotHaveBean(MetricsRecorder.class));
    }

    @Test
    void shouldKeepCustomMetricsRecorder_whenUserProvidesOne() {
        MetricsRecorder customRecorder = mock(MetricsRecorder.class);

        contextRunner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(MetricsRecorder.class, () -> customRecorder)
                .run(context -> assertThat(context.getBean(MetricsRecorder.class)).isSameAs(customRecorder));
    }
}
