package io.github.fatmii.nacoswebconfig.nacos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.nacos.api.NacosFactory;
import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import java.time.Duration;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Real-server contract test; run explicitly with {@code -Dnacos.test.server=host:port}. */
class NacosConfigSourceIT {
    @Test
    void observesInitialDeletionPublishDeleteAndListenerRemoval() throws Exception {
        var server = System.getProperty("nacos.test.server");
        if (server == null || server.isBlank()) {
            throw new IllegalStateException("nacos.test.server is required for this integration test");
        }
        var group = "NWC_I2_" + UUID.randomUUID().toString().replace("-", "");
        var dataId = "adapter.json";
        var ref = new ConfigRef(group, dataId);
        var properties = new Properties();
        properties.setProperty("serverAddr", server);
        var namespace = System.getProperty("nacos.test.namespace");
        if (namespace != null && !namespace.isBlank()) {
            properties.setProperty("namespace", namespace);
        }
        var publisher = NacosFactory.createConfigService(properties);
        var events = new LinkedBlockingQueue<SourceEvent>();
        var source = NacosConfigSource.managed(properties, Duration.ofSeconds(5));
        try {
            publisher.removeConfig(dataId, group);
            var watch = source.watch(Set.of(ref), events::add);
            assertInstanceOf(SourceEvent.Deleted.class, events.poll(10, TimeUnit.SECONDS));

            assertTrue(publisher.publishConfig(dataId, group, "{\"version\":1}"));
            var value = assertInstanceOf(
                    SourceEvent.Value.class, events.poll(10, TimeUnit.SECONDS));
            assertEquals("{\"version\":1}", value.content());

            assertTrue(publisher.removeConfig(dataId, group));
            assertInstanceOf(SourceEvent.Deleted.class, events.poll(10, TimeUnit.SECONDS));

            watch.close();
            assertTrue(publisher.publishConfig(dataId, group, "{\"late\":true}"));
            assertNull(events.poll(1, TimeUnit.SECONDS));
        } finally {
            source.close();
            publisher.removeConfig(dataId, group);
            publisher.shutDown();
        }
    }
}
