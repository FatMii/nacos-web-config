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
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = WebConfigStreamHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.stream.heartbeat=10s",
            "nacos-web-config.stream.max-connections=100"
        })
class WebConfigFanOutHttpTest {
    private static final int CONNECTIONS = 100;

    @LocalServerPort int port;
    @Autowired WebConfigStreamHttpTest.TestSource source;

    @Test
    @Timeout(30)
    void oneUpdateReachesOneHundredBoundedSseConnections() throws Exception {
        var client = HttpClient.newHttpClient();
        var streams = new ArrayList<InputStream>(CONNECTIONS);
        var readers = new ArrayList<BufferedReader>(CONNECTIONS);
        try {
            for (int index = 0; index < CONNECTIONS; index++) {
                var response = client.send(request(), HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, response.statusCode(), "connection " + index + " should be accepted");
                streams.add(response.body());
                var reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                readers.add(reader);
                assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));
            }

            var rejected = client.send(
                    request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(429, rejected.statusCode());
            assertTrue(rejected.body().contains("\"code\":\"CONNECTION_LIMIT\""));

            source.value("{\"marker\":\"fan-out-100\"}");
            for (int index = 0; index < CONNECTIONS; index++) {
                assertTrue(awaitText(readers.get(index), "fan-out-100").contains("fan-out-100"),
                        "connection " + index + " should receive the update");
            }
        } finally {
            for (var stream : streams) stream.close();
        }

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
}
