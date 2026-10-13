package io.github.fatmii.nacoswebconfig.example.secured;

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
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/** Proves that with {@code access: authenticated} only an authenticated caller can open the stream. */
@SpringBootTest(
        classes = SecuredDemoApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(NacosWebConfigTestConfiguration.class)
class SecuredDemoApplicationHttpTest {
    @LocalServerPort int port;
    @Autowired InMemoryWebConfigSource source;

    @Test
    void anonymousStreamIsRefusedAndAuthenticatedStreamDeliversTheSnapshot() throws Exception {
        source.value("ui", "{\"banner\":{\"enabled\":true,\"text\":\"ops\"},\"refreshIntervalMs\":30000}");
        var client = HttpClient.newHttpClient();

        var anonymous = client.send(streamRequest(null), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, anonymous.statusCode());

        var credentials = Base64.getEncoder().encodeToString(
                "demo:demo".getBytes(StandardCharsets.UTF_8));
        var authorized = client.send(
                streamRequest(credentials), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, authorized.statusCode());
        try (var reader = new BufferedReader(
                new InputStreamReader(authorized.body(), StandardCharsets.UTF_8))) {
            assertEquals("event:snapshot", nextNonBlankLine(reader).replace(" ", ""));
            assertTrue(nextNonBlankLine(reader).contains("\"text\":\"ops\""));
        }
    }

    private HttpRequest streamRequest(String basicCredentials) {
        var builder = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET();
        return basicCredentials == null
                ? builder.build()
                : builder.header("Authorization", "Basic " + basicCredentials).build();
    }

    private String nextNonBlankLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }
}
