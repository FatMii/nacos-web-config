package io.github.fatmii.nacoswebconfig.nacos;

import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.exception.NacosException;
import java.util.Properties;

/** Creates the external Nacos client used by a managed configuration source. */
@FunctionalInterface
public interface NacosConfigServiceFactory {
    /**
     * Creates a client from standard Nacos Java SDK properties.
     *
     * @param properties server address, namespace and optional credentials
     * @return newly created client whose ownership is transferred to the caller
     * @throws NacosException when the Nacos SDK cannot create the client
     */
    ConfigService create(Properties properties) throws NacosException;
}
