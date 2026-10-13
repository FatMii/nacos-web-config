package io.github.fatmii.nacoswebconfig.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRejectionListener;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigSink;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import io.github.fatmii.nacoswebconfig.core.SourceError;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import io.github.fatmii.nacoswebconfig.core.Watch;
import io.github.fatmii.nacoswebconfig.mvc.WebConfigStreamStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Covers the optional Actuator surface. The runtime here is composed exactly like production
 * composes it, so breaking a read-only seam or a rejection sink fails this test instead of quietly
 * disabling an operational control.
 */
class NacosWebConfigObservabilityAutoConfigurationTest {
    private static final ConfigRef UI_REF = new ConfigRef("WEB_DEMO", "web-demo.public.json");
    private static final String READY_CONTENT =
            "{\"banner\":\"sentinel-banner-text\",\"refreshIntervalMs\":30000}";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(
                    NacosWebConfigAutoConfiguration.class,
                    NacosWebConfigHealthAutoConfiguration.class,
                    NacosWebConfigMetricsAutoConfiguration.class))
            .withUserConfiguration(ObservabilityConfiguration.class)
            .withPropertyValues(
                    "nacos-web-config.enabled=true",
                    "nacos-web-config.access=public",
                    "nacos-web-config.exposures.ui.data-id=web-demo.public.json");

    @Test
    void healthReportsSourceAndPerExposureState() {
        contextRunner.run(context -> {
            var health = context.getBean(HealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("source", "available");
            assertThat(exposure(health, "ui"))
                    .containsEntry("status", "ready")
                    .containsEntry("hasValue", true);
        });
    }

    @Test
    void healthGoesDownWhenTheSourceCannotReachNacos() {
        contextRunner.run(context -> {
            context.getBean(FakeSource.class).unreachable();

            var health = context.getBean(HealthIndicator.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("source", "unavailable");
            assertThat(exposure(health, "ui"))
                    .containsEntry("status", "unavailable")
                    .containsEntry("errorCode", "SOURCE_UNAVAILABLE")
                    // The retained last-known-good value keeps browsers served, so only the
                    // source itself may fail the host application's readiness probe.
                    .containsEntry("hasValue", true);
        });
    }

    @Test
    void healthNeverLeaksConfigurationContent() {
        contextRunner.run(context -> {
            var health = context.getBean(HealthIndicator.class).health();

            assertThat(String.valueOf(health.getDetails())).doesNotContain("sentinel-banner-text");
            assertThat(String.valueOf(health.getDetails())).doesNotContain(READY_CONTENT);
        });
    }

    @Test
    void healthBacksOffWhenTheHostDisablesIt() {
        contextRunner
                .withPropertyValues("management.health.nacosWebConfig.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthIndicator.class));
    }

    @Test
    void streamAndRejectionCountersArePublishedWithoutRecordingContent() {
        contextRunner.run(context -> {
            var registry = context.getBean(MeterRegistry.class);
            assertThat(context).hasSingleBean(WebConfigStreamStatus.class);

            assertThat(registry.get("nacos.webconfig.stream.connections").gauge().value()).isZero();
            assertThat(registry.get("nacos.webconfig.stream.events")
                    .tag("kind", "change").functionCounter().count()).isZero();

            context.getBean(FakeSource.class).publish("{not-json");
            context.getBean(FakeSource.class).publish("{not-json");

            assertThat(registry.get("nacos.webconfig.rejections")
                    .tag("code", "INVALID_JSON").functionCounter().count())
                    .isEqualTo(2.0);
            assertThat(registry.getMeters())
                    .noneMatch(meter -> String.valueOf(meter.getId()).contains("not-json"));
        });
    }

    @Test
    void micrometerWithoutActuatorMetricsDoesNotBreakAHost() {
        // nacos-client puts micrometer-core on every consumer's classpath, so classes alone must
        // never be enough to make this library demand a registry. Such a host must still start.
        runnerWithoutRegistry().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ConfigRuntime.class);
            assertThat(context).doesNotHaveBean(MeterRegistry.class);
        });
    }

    private WebApplicationContextRunner runnerWithoutRegistry() {
        return new WebApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()))
                .withConfiguration(AutoConfigurations.of(
                        NacosWebConfigAutoConfiguration.class,
                        NacosWebConfigHealthAutoConfiguration.class,
                        NacosWebConfigMetricsAutoConfiguration.class))
                .withUserConfiguration(NoRegistryConfiguration.class)
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures.ui.data-id=web-demo.public.json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exposure(
            org.springframework.boot.actuate.health.Health health, String alias) {
        var exposures = (Map<String, Object>) health.getDetails().get("exposures");
        return (Map<String, Object>) exposures.get(alias);
    }

    @Configuration(proxyBeanMethods = false)
    static class NoRegistryConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        FakeSource fakeSource() {
            return new FakeSource();
        }

        @Bean
        ConfigRuntime configRuntime(
                FakeSource source, ObjectProvider<ConfigRejectionListener> rejectionListeners) {
            return NacosWebConfigAutoConfiguration.startWithRejectionLogging(
                    List.of(new ExposureDefinition("ui", UI_REF, 65_536)),
                    source,
                    rejectionListeners.orderedStream().toList());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObservabilityConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        /** Mirrors what Actuator's own post-processor does for every registered binder. */
        @Bean
        InitializingBean bindMeters(MeterRegistry registry, ObjectProvider<MeterBinder> binders) {
            return () -> binders.orderedStream().forEach(binder -> binder.bindTo(registry));
        }

        @Bean
        FakeSource fakeSource() {
            return new FakeSource();
        }

        @Bean
        ConfigRuntime configRuntime(
                FakeSource source, ObjectProvider<ConfigRejectionListener> rejectionListeners) {
            return NacosWebConfigAutoConfiguration.startWithRejectionLogging(
                    List.of(new ExposureDefinition("ui", UI_REF, 65_536)),
                    source,
                    rejectionListeners.orderedStream().toList());
        }
    }

    static final class FakeSource implements ConfigSource {
        private volatile ConfigSink sink;
        private volatile boolean available = true;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            this.sink = sink;
            refs.forEach(ref -> sink.accept(new SourceEvent.Value(ref, READY_CONTENT)));
            return () -> {};
        }

        void publish(String content) {
            sink.accept(new SourceEvent.Value(UI_REF, content));
        }

        void unreachable() {
            available = false;
            sink.accept(new SourceEvent.Unavailable(UI_REF, SourceError.UNAVAILABLE));
        }

        @Override
        public void close() {}
    }
}
