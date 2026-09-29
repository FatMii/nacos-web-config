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
        classes = WebConfigMaxKeysHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.enabled=true",
            "nacos-web-config.access=public",
            "nacos-web-config.stream.max-keys-per-connection=1",
            "nacos-web-config.exposures.ui.data-id=app-web.public.json",
            "nacos-web-config.exposures.flags.data-id=feature-flags.public.json"
        })
class WebConfigMaxKeysHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "app-web.public.json");
    private static final ConfigRef FLAGS =
            new ConfigRef("DEFAULT_GROUP", "feature-flags.public.json");
    @LocalServerPort int port;

    @Test
    void configuredKeyLimitRejectsOversizedRequestBeforeOpeningStream() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port
                                + "/_web-config/v1/stream?key=ui&key=flags"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertEquals(400, response.statusCode());
            var json = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"code\":\"INVALID_REQUEST\""));
            assertTrue(!json.contains("app-web.public.json"));
            assertTrue(!json.contains("feature-flags.public.json"));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        ConfigRuntime configRuntime() {
            return ConfigRuntime.start(
                    List.of(
                            new ExposureDefinition("ui", UI, 65_536),
                            new ExposureDefinition("flags", FLAGS, 65_536)),
                    new TestSource());
        }
    }

    static final class TestSource implements ConfigSource {
        @Override
        public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            sink.accept(new SourceEvent.Value(UI, "{\"theme\":\"dark\"}"));
            sink.accept(new SourceEvent.Value(FLAGS, "{\"checkout\":true}"));
            return () -> {};
        }

        @Override public void close() {}
    }
}
