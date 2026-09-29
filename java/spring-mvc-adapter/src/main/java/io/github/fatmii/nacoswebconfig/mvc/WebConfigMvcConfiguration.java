package io.github.fatmii.nacoswebconfig.mvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import java.time.Duration;
import org.springframework.util.unit.DataSize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring MVC beans for the web configuration SSE endpoint. */
@Configuration(proxyBeanMethods = false)
public class WebConfigMvcConfiguration {
    @Bean
    WebConfigStreamService webConfigStreamService(
            ConfigRuntime runtime,
            ObjectMapper mapper,
            @Value("${nacos-web-config.stream.heartbeat:15s}") Duration heartbeat,
            @Value("${nacos-web-config.stream.connection-timeout:30m}") Duration connectionTimeout,
            @Value("${nacos-web-config.stream.max-connections:1000}") int maxConnections,
            @Value("${nacos-web-config.stream.max-pending-bytes-per-connection:1MB}")
                    DataSize maxPendingBytes) {
        return new WebConfigStreamService(
                runtime, mapper, heartbeat, connectionTimeout, maxConnections,
                maxPendingBytes.toBytes());
    }

    @Bean
    WebConfigStreamController webConfigStreamController(
            WebConfigStreamService streams,
            ObjectProvider<WebConfigAccessPolicy> accessPolicy,
            @Value("${nacos-web-config.stream.max-keys-per-connection:32}") int maxKeys) {
        return new WebConfigStreamController(
                streams, accessPolicy.getIfAvailable(() -> principal -> true), maxKeys);
    }

    @Bean
    WebConfigErrorHandler webConfigErrorHandler() {
        return new WebConfigErrorHandler();
    }
}
