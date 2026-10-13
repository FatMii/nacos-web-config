package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nacos-web-config.stream.heartbeat=500ms")
class WebConfigStreamStatusHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "app-web.public.json");

    @LocalServerPort int port;
    @Autowired TestSource source;
    @Autowired WebConfigStreamStatus status;

    @Test
    void readOnlyViewCountsAcceptedFramesAndReleasesClosedConnections() throws Exception {
        assertEquals(0, status.activeConnections());

        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        var reads = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "sse-status-reader");
            thread.setDaemon(true);
            return thread;
        });
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextNonBlankLine(reader).replace(" ", ""));
            nextNonBlankLine(reader);

            source.value("{\"theme\":\"light\"}");
            assertEquals("event:change", nextNonBlankLine(reader).replace(" ", ""));
            nextNonBlankLine(reader);

            // Heartbeat is 500ms but the budget is 10s: the reader thread competes with
            // whatever else the build machine happens to be running.
            var heartbeat = reads.submit(() -> nextNonBlankLine(reader));
            assertEquals(":ping", heartbeat.get(10, TimeUnit.SECONDS).replace(" ", ""));

            assertEquals(1, status.activeConnections());
            assertEquals(1, status.snapshotEvents());
            assertEquals(1, status.changeEvents());
            assertTrue(status.heartbeatEvents() >= 1, "the observed ping must be counted");

            var laterHeartbeats = status.heartbeatEvents();
            var second = reads.submit(() -> nextNonBlankLine(reader));
            assertEquals(":ping", second.get(10, TimeUnit.SECONDS).replace(" ", ""));
            assertTrue(
                    status.heartbeatEvents() > laterHeartbeats,
                    "the view must track the live engine instead of a copied value");
        } finally {
            reads.shutdownNow();
        }

        awaitConnections(0);
    }

    private void awaitConnections(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (status.activeConnections() != expected) {
            if (System.nanoTime() > deadline) {
                fail("active connections stayed at " + status.activeConnections()
                        + " instead of " + expected);
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private String nextNonBlankLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(WebConfigMvcConfiguration.class)
    static class App {
        @Bean
        TestSource testSource() {
            return new TestSource();
        }

        @Bean
        ConfigRuntime configRuntime(TestSource source) {
            return ConfigRuntime.start(List.of(new ExposureDefinition("ui", UI, 65_536)), source);
        }
    }

    static final class TestSource implements ConfigSource {
        private volatile ConfigSink sink;

        @Override public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            this.sink = sink;
            sink.accept(new SourceEvent.Value(UI, "{\"theme\":\"dark\"}"));
            return () -> {};
        }

        void value(String content) {
            sink.accept(new SourceEvent.Value(UI, content));
        }

        @Override public void close() {}
    }
}
