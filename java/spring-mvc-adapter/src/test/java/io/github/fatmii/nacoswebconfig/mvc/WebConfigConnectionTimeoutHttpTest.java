package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = WebConfigStreamHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.stream.heartbeat=50ms",
            "nacos-web-config.stream.connection-timeout=300ms"
        })
class WebConfigConnectionTimeoutHttpTest {
    @LocalServerPort int port;

    @Test
    void configuredConnectionTimeoutEndsStreamDespiteHeartbeats() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        var reads = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "sse-timeout-test-reader");
            thread.setDaemon(true);
            return thread;
        });
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));
            assertTrue(nextLine(reader).contains("\"seq\":0"));
            reads.submit(() -> readUntilClosed(reader)).get(10, TimeUnit.SECONDS);
        } finally {
            reads.shutdownNow();
        }
    }

    private void readUntilClosed(BufferedReader reader) {
        try {
            while (reader.readLine() != null) {}
        } catch (IOException disconnected) {}
    }

    private String nextLine(BufferedReader reader) throws IOException {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }
}
