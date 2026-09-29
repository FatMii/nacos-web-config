package io.github.fatmii.nacoswebconfig.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class NacosWebConfigPropertiesTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsDocumentedConfigurationAndDefaults() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=authenticated",
                        "nacos-web-config.source.mode=managed",
                        "nacos-web-config.nacos.server-addr=127.0.0.1:8848",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    var properties = context.getBean(NacosWebConfigProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.path()).isEqualTo("/_web-config");
                    assertThat(properties.access()).isEqualTo(Access.AUTHENTICATED);
                    assertThat(properties.source().mode()).isEqualTo(SourceMode.MANAGED);
                    assertThat(properties.nacos().serverAddr()).isEqualTo("127.0.0.1:8848");
                    assertThat(properties.nacos().namespace()).isEqualTo("public");
                    assertThat(properties.nacos().timeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.exposures().get("ui").group()).isEqualTo("DEFAULT_GROUP");
                    assertThat(properties.exposures().get("ui").maxBytes()).isEqualTo(65_536);
                    assertThat(properties.stream().heartbeat()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(properties.stream().connectionTimeout()).isEqualTo(Duration.ofMinutes(30));
                    assertThat(properties.stream().maxConnections()).isEqualTo(1_000);
                    assertThat(properties.stream().maxKeysPerConnection()).isEqualTo(32);
                    assertThat(properties.stream().maxPendingBytesPerConnection())
                            .isEqualTo(DataSize.ofMegabytes(1));
                    assertThat(properties.startup().failFast()).isFalse();
                });
    }

    @Test
    void enabledConfigurationRequiresExplicitAccessMode() {
        contextRunner
                .withPropertyValues("nacos-web-config.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.access must be explicitly configured as public or authenticated");
                });
    }

    @Test
    void enabledConfigurationRequiresAtLeastOneExposure() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.exposures must contain at least one entry");
                });
    }

    @Test
    void enabledConfigurationRequiresAbsolutePathWithoutTrailingSlash() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.path=frontend-config/",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.path must start with '/' and must not end with '/'");
                });
    }

    @Test
    void enabledConfigurationRequiresPositiveMaxKeysPerConnection() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.stream.max-keys-per-connection=0",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.stream.max-keys-per-connection must be positive");
                });
    }

    @Test
    void enabledConfigurationRequiresPositiveHeartbeat() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.stream.heartbeat=0s",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.stream.heartbeat must be positive");
                });
    }

    @Test
    void enabledConfigurationRequiresPositiveConnectionTimeout() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.stream.connection-timeout=0s",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.stream.connection-timeout must be positive");
                });
    }

    @Test
    void enabledConfigurationRequiresPositiveMaxConnections() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.stream.max-connections=0",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.stream.max-connections must be positive");
                });
    }

    @Test
    void enabledConfigurationRequiresPositivePendingBytesLimit() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.stream.max-pending-bytes-per-connection=0B",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.stream.max-pending-bytes-per-connection must be positive");
                });
    }

    @Test
    void enabledConfigurationRejectsInvalidExposureAlias() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures[.ui].data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "invalid nacos-web-config exposure alias: .ui");
                });
    }

    @Test
    void enabledConfigurationRequiresExposureDataId() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures.ui.group=DEFAULT_GROUP")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.exposures.ui.data-id must not be blank");
                });
    }

    @Test
    void enabledConfigurationRejectsBlankExposureGroup() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json",
                        "nacos-web-config.exposures.ui.group=   ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.exposures.ui.group must not be blank");
                });
    }

    @Test
    void enabledConfigurationRequiresPositiveExposureMaxBytes() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json",
                        "nacos-web-config.exposures.ui.max-bytes=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.exposures.ui.max-bytes must be positive");
                });
    }

    @Test
    void managedSourceRequiresNacosServerAddress() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=managed",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.nacos.server-addr is required for source.mode=managed");
                });
    }

    @Test
    void beanSourceRequiresBeanName() {
        contextRunner
                .withPropertyValues(
                        "nacos-web-config.enabled=true",
                        "nacos-web-config.access=public",
                        "nacos-web-config.source.mode=bean",
                        "nacos-web-config.exposures.ui.data-id=app-web.public.json")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "nacos-web-config.source.bean-name is required for source.mode=bean");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NacosWebConfigProperties.class)
    static class PropertiesConfiguration {}
}
