package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
        classes = WebConfigStreamHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nacos-web-config.stream.heartbeat=9s")
class WebConfigStoppedHttpTest {
    @LocalServerPort int port;
    @Autowired WebConfigStreamService streams;

    @Test
    void stoppedModuleRejectsNewStreamWithStableRetryableError() throws Exception {
        streams.stop();
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(503, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("application/json"));
        assertTrue(response.body().contains("\"code\":\"MODULE_STOPPED\""));
        assertTrue(!response.body().contains("app-web.public.json"));
    }
}
