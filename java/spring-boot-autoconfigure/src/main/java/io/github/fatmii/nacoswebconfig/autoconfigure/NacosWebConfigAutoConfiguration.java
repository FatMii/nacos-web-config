package io.github.fatmii.nacoswebconfig.autoconfigure;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRejectionListener;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.ConfigStatus;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import io.github.fatmii.nacoswebconfig.mvc.WebConfigMvcConfiguration;
import io.github.fatmii.nacoswebconfig.mvc.WebConfigAccessPolicy;
import io.github.fatmii.nacoswebconfig.nacos.NacosConfigSource;
import io.github.fatmii.nacoswebconfig.nacos.NacosConfigServiceFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Activates the web configuration stream for an explicitly enabled MVC application. */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
@ConditionalOnClass({DispatcherServlet.class, SseEmitter.class})
@ConditionalOnProperty(prefix = "nacos-web-config", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NacosWebConfigProperties.class)
@Import(WebConfigMvcConfiguration.class)
public class NacosWebConfigAutoConfiguration {
    /** Applies the explicitly selected browser access mode without owning authentication. */
    @Bean
    WebConfigAccessPolicy nacosWebConfigAccessPolicy(NacosWebConfigProperties properties) {
        return principal -> properties.access() == Access.PUBLIC || principal != null;
    }

    /** Builds the runtime from fixed YAML exposures while preserving a user-provided override. */
    @Bean
    @ConditionalOnMissingBean
    ConfigRuntime nacosWebConfigRuntime(
            NacosWebConfigProperties properties,
            ListableBeanFactory beanFactory,
            NacosConfigServiceFactory configServiceFactory,
            ObjectProvider<ConfigRejectionListener> rejectionListeners) {
        var source = selectSource(properties, beanFactory, configServiceFactory);
        var definitions = new ArrayList<ExposureDefinition>();
        if (properties.exposures() != null) {
            properties.exposures().forEach((alias, exposure) -> definitions.add(new ExposureDefinition(
                    alias,
                    new ConfigRef(exposure.group(), exposure.dataId()),
                    exposure.maxBytes())));
        }
        var runtime = startWithRejectionLogging(
                definitions, source, rejectionListeners.orderedStream().toList());
        if (properties.startup().failFast()) {
            rejectUnavailableInitialState(runtime, definitions);
        }
        return runtime;
    }

    private static final org.slf4j.Logger REJECTION_LOG =
            org.slf4j.LoggerFactory.getLogger("io.github.fatmii.nacoswebconfig.REJECTIONS");

    /**
     * Starts a runtime whose every content rejection is WARN-logged for operators (key, stable
     * code, rejected-content sha256; never the raw body).
     */
    public static ConfigRuntime startWithRejectionLogging(
            Collection<ExposureDefinition> definitions,
            ConfigSource source) {
        return startWithRejectionLogging(definitions, source, List.of());
    }

    /**
     * Same as {@link #startWithRejectionLogging(Collection, ConfigSource)}, additionally fanning
     * every rejection out to operator-supplied sinks. Production auto-configuration and the
     * starter-test runtime both go through here so the two paths stay observable identically. A
     * failing sink never affects state, delivery, or the other sinks.
     */
    public static ConfigRuntime startWithRejectionLogging(
            Collection<ExposureDefinition> definitions,
            ConfigSource source,
            Collection<ConfigRejectionListener> operatorListeners) {
        var extras = List.copyOf(operatorListeners);
        ConfigRejectionListener listener = (alias, code, rejectedHash) -> {
            REJECTION_LOG.warn(
                    "rejected content for exposure '{}' ({}); serving last known good value; rejected sha256={}",
                    alias, code, rejectedHash);
            for (var extra : extras) {
                try {
                    extra.onRejected(alias, code, rejectedHash);
                } catch (RuntimeException ignored) {
                    // Diagnostics must never alter state or delivery.
                }
            }
        };
        return ConfigRuntime.start(definitions, source, listener);
    }

    /** Supplies the official Nacos client creation boundary unless the host replaces it. */
    @Bean
    @ConditionalOnMissingBean
    NacosConfigServiceFactory nacosConfigServiceFactory() {
        return NacosFactory::createConfigService;
    }

    private NacosConfigSource selectSource(
            NacosWebConfigProperties properties,
            ListableBeanFactory beanFactory,
            NacosConfigServiceFactory configServiceFactory) {
        if (properties.source().mode() == SourceMode.MANAGED) {
            return managedSource(properties, configServiceFactory);
        }
        if (properties.source().mode() == SourceMode.BEAN) {
            if (properties.source().beanName().isBlank()) {
                throw new IllegalStateException(
                        "nacos-web-config.source.bean-name is required for source.mode=bean");
            }
            return NacosConfigSource.borrowed(
                    beanFactory.getBean(properties.source().beanName(), ConfigService.class),
                    properties.nacos().timeout());
        }
        if (properties.source().mode() == SourceMode.AUTO) {
            var candidates = beanFactory.getBeansOfType(ConfigService.class);
            if (candidates.size() == 1) {
                return NacosConfigSource.borrowed(
                        candidates.values().iterator().next(), properties.nacos().timeout());
            }
            if (candidates.size() > 1) {
                throw new IllegalStateException(
                        "source.mode=auto found multiple ConfigService beans; use source.mode=bean");
            }
            if (properties.nacos().serverAddr().isBlank()) {
                throw new IllegalStateException(
                        "nacos-web-config.nacos.server-addr is required when source.mode=auto finds no ConfigService bean");
            }
            return managedSource(properties, configServiceFactory);
        }
        throw new IllegalStateException("Unsupported source mode");
    }

    private NacosConfigSource managedSource(
            NacosWebConfigProperties properties,
            NacosConfigServiceFactory configServiceFactory) {
        var nacos = new Properties();
        putIfNotBlank(nacos, "serverAddr", properties.nacos().serverAddr());
        putIfNotBlank(nacos, "namespace", properties.nacos().namespace());
        putIfNotBlank(nacos, "username", properties.nacos().username());
        putIfNotBlank(nacos, "password", properties.nacos().password());
        return NacosConfigSource.managed(
                nacos, properties.nacos().timeout(), configServiceFactory);
    }

    private void putIfNotBlank(Properties target, String key, String value) {
        if (!value.isBlank()) {
            target.setProperty(key, value);
        }
    }

    private void rejectUnavailableInitialState(
            ConfigRuntime runtime, ArrayList<ExposureDefinition> definitions) {
        var aliases = new HashSet<String>();
        definitions.forEach(definition -> aliases.add(definition.alias()));
        var unavailable = runtime.snapshot(aliases).entries().entrySet().stream()
                .filter(entry -> entry.getValue().status() == ConfigStatus.UNAVAILABLE)
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .toList();
        if (unavailable.isEmpty()) return;

        var failure = new IllegalStateException(
                "nacos-web-config.startup.fail-fast rejected unavailable exposures: "
                        + String.join(", ", unavailable));
        // ConfigRuntime owns its source. A failed Spring bean must release listeners and any
        // managed ConfigService immediately because the container cannot destroy an unreturned bean.
        try {
            runtime.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
        throw failure;
    }
}
