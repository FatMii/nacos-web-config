package io.github.fatmii.nacoswebconfig.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.nacos.NacosConfigServiceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class NacosWebConfigAutoConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(NacosWebConfigAutoConfiguration.class))
            .withUserConfiguration(ClientFactoryConfiguration.class);

    @Test
    void autoSourceWithoutBeanRequiresNacosServerAddress() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=auto",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.nacos.server-addr is required when source.mode=auto finds no ConfigService bean");
                });
    }

    @Test
    void failFastRejectsAnExposureWhoseInitialReadIsUnavailable() {
        contextRunner
                .withUserConfiguration(UnavailableClientConfiguration.class)
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=bean",
                        "nacos-web-config.source.bean-name=unavailableConfigService",
                        "nacos-web-config.startup.fail-fast=true",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.startup.fail-fast rejected unavailable exposures: ui");
                });
    }

    @Test
    void failFastDisabledAllowsAnUnavailableInitialRead() {
        contextRunner
                .withUserConfiguration(UnavailableClientConfiguration.class)
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=bean",
                        "nacos-web-config.source.bean-name=unavailableConfigService",
                        "nacos-web-config.startup.fail-fast=false",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> assertThat(context).hasSingleBean(ConfigRuntime.class));
    }

    @Test
    void failFastAcceptsAnAuthoritativeDeletion() {
        contextRunner
                .withUserConfiguration(DeletedClientConfiguration.class)
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=bean",
                        "nacos-web-config.source.bean-name=deletedConfigService",
                        "nacos-web-config.startup.fail-fast=true",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> assertThat(context).hasSingleBean(ConfigRuntime.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class ClientFactoryConfiguration {
        @Bean
        NacosConfigServiceFactory nacosConfigServiceFactory() {
            return properties -> {
                throw new IllegalStateException("client factory must not be called");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UnavailableClientConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ConfigService unavailableConfigService() throws NacosException {
            var service = mock(ConfigService.class);
            when(service.getConfigAndSignListener(anyString(), anyString(), anyLong(), any()))
                    .thenThrow(new NacosException(500, "temporarily unavailable"));
            return service;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DeletedClientConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        ConfigService deletedConfigService() throws NacosException {
            var service = mock(ConfigService.class);
            when(service.getConfigAndSignListener(anyString(), anyString(), anyLong(), any()))
                    .thenReturn(null);
            return service;
        }
    }
}
