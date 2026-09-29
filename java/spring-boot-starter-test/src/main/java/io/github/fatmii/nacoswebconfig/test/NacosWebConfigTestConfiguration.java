package io.github.fatmii.nacoswebconfig.test;

import io.github.fatmii.nacoswebconfig.autoconfigure.NacosWebConfigAutoConfiguration;
import io.github.fatmii.nacoswebconfig.autoconfigure.NacosWebConfigProperties;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import java.util.LinkedHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test beans that replace Nacos with an alias-driven in-memory source. */
@TestConfiguration(proxyBeanMethods = false)
public class NacosWebConfigTestConfiguration {
    /** Creates the source that a test uses to publish configuration events. */
    @Bean
    @ConditionalOnMissingBean
    InMemoryWebConfigSource inMemoryWebConfigSource(NacosWebConfigProperties properties) {
        var refs = new LinkedHashMap<String, ConfigRef>();
        properties.exposures().forEach((alias, exposure) -> refs.put(
                alias, new ConfigRef(exposure.group(), exposure.dataId())));
        return new InMemoryWebConfigSource(refs);
    }

    /** Creates the real runtime from YAML exposures while making production Nacos setup back off. */
    @Bean
    @ConditionalOnMissingBean
    ConfigRuntime nacosWebConfigTestRuntime(
            NacosWebConfigProperties properties, InMemoryWebConfigSource source) {
        var definitions = properties.exposures().entrySet().stream()
                .map(entry -> new ExposureDefinition(
                        entry.getKey(),
                        new ConfigRef(entry.getValue().group(), entry.getValue().dataId()),
                        entry.getValue().maxBytes()))
                .toList();
        return NacosWebConfigAutoConfiguration.startWithRejectionLogging(definitions, source);
    }
}
