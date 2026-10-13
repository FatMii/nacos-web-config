package io.github.fatmii.nacoswebconfig.autoconfigure;

import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.mvc.WebConfigStreamStatus;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.function.ToDoubleFunction;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Publishes operational metrics for a host that runs Spring Boot Actuator.
 *
 * <p>Actuator is an optional dependency of this library. The classpath guard has to name an
 * Actuator class rather than only micrometer because the Nacos client already puts micrometer-core
 * in every consumer's tree, so a host without Actuator would otherwise activate here. Metrics are
 * published as {@link MeterBinder} beans, which Actuator binds whenever a registry exists; no bean
 * of this configuration needs a registry to be created first, so a host cannot fail to start
 * because of them.
 *
 * <p>Tags come from the YAML allowlist and the fixed status codes, so no request-supplied value
 * becomes a tag, and rejected content is only ever counted, never recorded.
 */
@AutoConfiguration(after = NacosWebConfigAutoConfiguration.class)
@ConditionalOnClass({MeterRegistry.class, MetricsAutoConfiguration.class})
@ConditionalOnBean(ConfigRuntime.class)
public class NacosWebConfigMetricsAutoConfiguration {
    /** Counts refused publishes; the reason code is the only tag. */
    @Bean
    @ConditionalOnMissingBean(name = "nacosWebConfigRejectionMetrics")
    NacosWebConfigRejectionMetrics nacosWebConfigRejectionMetrics() {
        return new NacosWebConfigRejectionMetrics();
    }

    /**
     * Reports live SSE traffic, and backs off when the host does not serve streams. Matched by
     * bean name: both beans of this configuration are {@link MeterBinder}s, so a type-level
     * missing-bean check would make them exclude each other.
     */
    @Bean
    @ConditionalOnBean(WebConfigStreamStatus.class)
    @ConditionalOnMissingBean(name = "nacosWebConfigStreamMetrics")
    MeterBinder nacosWebConfigStreamMetrics(WebConfigStreamStatus streams) {
        return registry -> {
            Gauge.builder(
                            "nacos.webconfig.stream.connections",
                            streams,
                            WebConfigStreamStatus::activeConnections)
                    .description("SSE streams currently open in this process")
                    .register(registry);
            eventCounter(registry, streams, "snapshot", WebConfigStreamStatus::snapshotEvents);
            eventCounter(registry, streams, "change", WebConfigStreamStatus::changeEvents);
            eventCounter(registry, streams, "heartbeat", WebConfigStreamStatus::heartbeatEvents);
        };
    }

    private static void eventCounter(
            MeterRegistry registry,
            WebConfigStreamStatus streams,
            String kind,
            ToDoubleFunction<WebConfigStreamStatus> count) {
        FunctionCounter.builder("nacos.webconfig.stream.events", streams, count)
                .tag("kind", kind)
                .description("Frames the container accepted for writing, by frame kind")
                .register(registry);
    }
}
