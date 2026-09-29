package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = WebConfigStreamHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.stream.heartbeat=100ms",
            "nacos-web-config.stream.max-connections=1"
        })
class WebConfigConnectionLimitHttpTest {
    @LocalServerPort int port;

    @Test
    void disconnectedClientReleasesItsSlot() throws Exception {
        var client = HttpClient.newHttpClient();
        var first = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(
                new InputStreamReader(first.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, first.statusCode());
            assertEquals("event:snapshot", reader.readLine().replace(" ", ""));

            var rejected = client.send(
                    request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(429, rejected.statusCode());
            assertTrue(rejected.headers().firstValue("Content-Type").orElse("")
                    .startsWith("application/json"));
            assertTrue(rejected.headers().firstValue("Retry-After").orElse("")
                    .matches("[1-9][0-9]*"));
            assertTrue(rejected.body().contains("\"code\":\"CONNECTION_LIMIT\""));
            assertTrue(!rejected.body().contains("app-web.public.json"));
        }

        try (var reopened = awaitAccepted(client)) {
            assertTrue(reopened.read() >= 0);
        }
    }

    private InputStream awaitAccepted(HttpClient client) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) return response.body();
            assertEquals(429, response.statusCode());
            response.body().close();
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("connection slot was not released after client disconnect");
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
    }
}
