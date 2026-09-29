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
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nacos-web-config.stream.heartbeat=500ms")
class WebConfigStreamHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "app-web.public.json");
    @LocalServerPort int port;
    @Autowired TestSource source;

    @Test
    void validKeyReceivesSnapshotFirstWithNonBufferingHeaders() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("no", response.headers().firstValue("X-Accel-Buffering").orElseThrow());
            assertEquals("event:snapshot", reader.readLine().replace(" ", ""));
            var data = reader.readLine();
            assertTrue(data.contains("\"protocol\":1"));
            assertTrue(data.contains("\"seq\":0"));
            assertTrue(data.contains("\"ui\""));
            assertTrue(data.contains("\"status\":\"ready\""));
        }
    }

    @Test
    void invalidKeysAreRejectedWithStableJsonErrorsBeforeStreaming() throws Exception {
        assertError("", "text/event-stream", 400, "INVALID_REQUEST");
        assertError("?key=ui&key=ui", "text/event-stream", 400, "INVALID_REQUEST");
        assertError("?key=secret", "text/event-stream", 400, "UNKNOWN_KEY");
        assertError("?key=bad%20key", "text/event-stream", 400, "INVALID_REQUEST");
        assertError("?" + "key=ui&".repeat(32) + "key=ui", "text/event-stream", 400, "INVALID_REQUEST");
        assertError("?key=ui", "application/json", 406, "SSE_REQUIRED");
    }

    @Test
    void changesFollowSnapshotWithStableStreamIdAndIncreasingSequence() throws Exception {
        source.value("{\"theme\":\"dark\"}");
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));
            var snapshot = nextLine(reader);
            var streamId = snapshot.replaceAll(".*\"streamId\":\"([^\"]+)\".*", "$1");

            source.value("{\"theme\":\"light\"}");
            assertEquals("event:change", nextLine(reader).replace(" ", ""));
            var first = nextLine(reader);
            assertTrue(first.contains("\"streamId\":\"" + streamId + "\""));
            assertTrue(first.contains("\"seq\":1"));
            assertTrue(first.contains("\"theme\":\"light\""));

            source.value("{\"theme\":\"contrast\"}");
            assertEquals("event:change", nextLine(reader).replace(" ", ""));
            var second = nextLine(reader);
            assertTrue(second.contains("\"streamId\":\"" + streamId + "\""));
            assertTrue(second.contains("\"seq\":2"));
            assertTrue(second.contains("\"theme\":\"contrast\""));
        }
    }

    @Test
    void idleStreamReceivesCommentHeartbeatWithoutBusinessSequence() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        var reads = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "sse-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));
            assertTrue(nextLine(reader).contains("\"seq\":0"));
            var heartbeat = reads.submit(() -> nextLine(reader));
            // Heartbeat is 500ms but the wait budget is 10s: the reader thread competes
            // with whatever else the build machine happens to be running.
            assertEquals(":ping", heartbeat.get(10, TimeUnit.SECONDS).replace(" ", ""));
        } finally {
            reads.shutdownNow();
        }
    }

    private String nextLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }

    private void assertError(String query, String accept, int status, String code) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream" + query))
                .header("Accept", accept)
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        assertTrue(response.body().contains("\"code\":\"" + code + "\""));
        assertTrue(!response.body().contains("app-web.public.json"));
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
            return ConfigRuntime.start(
                    List.of(new ExposureDefinition("ui", UI, 65_536)), source);
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
