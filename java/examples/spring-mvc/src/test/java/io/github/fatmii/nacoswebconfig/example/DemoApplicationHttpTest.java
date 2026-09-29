package io.github.fatmii.nacoswebconfig.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fatmii.nacoswebconfig.test.InMemoryWebConfigSource;
import io.github.fatmii.nacoswebconfig.test.NacosWebConfigTestConfiguration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Proves that a normal MVC host gains the web-config endpoint only from its dependency and YAML. */
@SpringBootTest(
        classes = DemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(NacosWebConfigTestConfiguration.class)
class DemoApplicationHttpTest {
    @LocalServerPort int port;
    @Autowired InMemoryWebConfigSource source;

    @Test
    void servesBusinessPageAndStarterManagedConfigStream() throws Exception {
        var client = HttpClient.newHttpClient();
        var demo = client.send(get("/demo"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, demo.statusCode());
        assertEquals("nacos-web-config demo", demo.body());

        var page = client.send(get("/"), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("Nacos Web Config Demo"));
        assertTrue(page.body().contains("Number.isInteger(interval)"));

        source.value("ui", "{\"banner\":{\"enabled\":true,\"text\":\"hello\"},\"refreshIntervalMs\":30000}");
        var stream = client.send(
                HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        try (var reader = new BufferedReader(
                new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextNonBlankLine(reader).replace(" ", ""));
            assertTrue(nextNonBlankLine(reader).contains("\"text\":\"hello\""));
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build();
    }

    private String nextNonBlankLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }
}
