package io.github.fatmii.nacoswebconfig.autoconfigure;

import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Adds the {@code nacosWebConfig} health component to a host that runs Spring Boot Actuator.
 *
 * <p>Actuator is an optional dependency of this library: without it on the classpath nothing here
 * activates and the host application is unchanged.
 */
@AutoConfiguration(after = NacosWebConfigAutoConfiguration.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(ConfigRuntime.class)
@ConditionalOnEnabledHealthIndicator("nacosWebConfig")
public class NacosWebConfigHealthAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "nacosWebConfigHealthIndicator")
    HealthIndicator nacosWebConfigHealthIndicator(ConfigRuntime runtime) {
        return new NacosWebConfigHealthIndicator(runtime);
    }
}
