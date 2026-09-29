package io.github.fatmii.nacoswebconfig.mvc;

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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = WebConfigPendingBytesHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.stream.heartbeat=10s",
            "nacos-web-config.stream.max-connections=2",
            "nacos-web-config.stream.max-pending-bytes-per-connection=2MB"
        })
class WebConfigPendingBytesHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "large.json");
    @LocalServerPort int port;
    @Autowired TestSource source;

    @Test
    void slowClientIsClosedWhileHealthyClientKeepsReceivingChanges() throws Exception {
        var client = HttpClient.newHttpClient();
        try (var slow = open(client);
                var healthy = open(client);
                var reader = new BufferedReader(
                        new InputStreamReader(healthy, StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));

            var work = Executors.newFixedThreadPool(2, task -> {
                var thread = new Thread(task, "pending-bytes-test-worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                var finalChange = work.submit(() -> awaitText(reader, "final-marker"));
                var publish = work.submit(() -> {
                    var payload = "x".repeat(32 * 1024);
                    for (int index = 0; index < 400; index++) {
                        source.value("{\"payload\":\"" + payload + "\",\"index\":" + index + "}");
                        TimeUnit.MILLISECONDS.sleep(3);
                    }
                    source.value("{\"payload\":\"final-marker\"}");
                    return null;
                });

                publish.get(15, TimeUnit.SECONDS);
                assertTrue(finalChange.get(15, TimeUnit.SECONDS).contains("final-marker"));
                try (var replacement = awaitAccepted(client)) {
                    assertTrue(replacement.read() >= 0);
                }
            } finally {
                work.shutdownNow();
            }
        }
    }

    private InputStream open(HttpClient client) throws Exception {
        var response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private InputStream awaitAccepted(HttpClient client) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            var response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) return response.body();
            assertEquals(429, response.statusCode());
            response.body().close();
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("slow connection did not release its slot");
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
    }

    private String awaitText(BufferedReader reader, String expected) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(expected)) return line;
        }
        throw new AssertionError("stream closed before receiving " + expected);
    }

    private String nextLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(WebConfigMvcConfiguration.class)
    static class App {
        @Bean TestSource testSource() {
            return new TestSource();
        }

        @Bean ConfigRuntime configRuntime(TestSource source) {
            return ConfigRuntime.start(
                    List.of(new ExposureDefinition("ui", UI, 64 * 1024)), source);
        }
    }

    static final class TestSource implements ConfigSource {
        private volatile ConfigSink sink;

        @Override public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            this.sink = sink;
            sink.accept(new SourceEvent.Value(UI, "{\"payload\":\"initial\"}"));
            return () -> {};
        }

        void value(String content) {
            sink.accept(new SourceEvent.Value(UI, content));
        }

        @Override public void close() {}
    }
}
