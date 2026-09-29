package io.github.fatmii.nacoswebconfig.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(
        classes = InMemoryWebConfigHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.enabled=true",
            "nacos-web-config.access=public",
            "nacos-web-config.exposures.ui.data-id=ignored-by-test-user.json"
        })
class InMemoryWebConfigHttpTest {
    @LocalServerPort int port;
    @Autowired InMemoryWebConfigSource source;

    @Test
    void testSourceDrivesTheRealStarterEndpointByPublicAlias() throws Exception {
        source.value("ui", "{\"theme\":\"dark\"}");

        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            assertEquals(200, response.statusCode());
            assertEquals("event:snapshot", nextLine(reader).replace(" ", ""));
            assertTrue(nextLine(reader).contains("\"theme\":\"dark\""));

            source.delete("ui");
            assertEquals("event:change", nextLine(reader).replace(" ", ""));
            assertTrue(nextLine(reader).contains("\"status\":\"deleted\""));
        }
    }

    @Test
    void rejectedContentWarnsOperatorsWithStableCodeAndNeverTheRawBody() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("io.github.fatmii.nacoswebconfig.REJECTIONS");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            source.value("ui", "secret-body-not-a-json-object");
            assertEquals(1, appender.list.size());
            var event = appender.list.get(0);
            assertEquals(ch.qos.logback.classic.Level.WARN, event.getLevel());
            var message = event.getFormattedMessage();
            assertTrue(message.contains("'ui'"));
            assertTrue(message.contains("INVALID_JSON"));
            assertTrue(!message.contains("secret-body"), "raw rejected content must never be logged");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private String nextLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null && line.isBlank()) {}
        return line;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(NacosWebConfigTestConfiguration.class)
    static class App {}
}
