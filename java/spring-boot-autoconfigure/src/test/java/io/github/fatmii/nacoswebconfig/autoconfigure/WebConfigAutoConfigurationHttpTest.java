package io.github.fatmii.nacoswebconfig.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigSink;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import io.github.fatmii.nacoswebconfig.core.Watch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
        classes = WebConfigAutoConfigurationHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.enabled=true",
            "nacos-web-config.access=public",
            "nacos-web-config.exposures.ui.data-id=app-web.public.json"
        })
class WebConfigAutoConfigurationHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "app-web.public.json");
    @LocalServerPort int port;

    @Test
    void enabledServletApplicationGetsStreamEndpointWithoutManualImport() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertEquals("event:snapshot", reader.readLine().replace(" ", ""));
            assertTrue(reader.readLine().contains("\"ui\""));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ConfigRuntime configRuntime() {
            return ConfigRuntime.start(
                    List.of(new ExposureDefinition("ui", UI, 65_536)), new TestSource());
        }
    }

    static final class TestSource implements ConfigSource {
        @Override
        public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            sink.accept(new SourceEvent.Value(UI, "{\"theme\":\"dark\"}"));
            return () -> {};
        }

        @Override public void close() {}
    }
}
