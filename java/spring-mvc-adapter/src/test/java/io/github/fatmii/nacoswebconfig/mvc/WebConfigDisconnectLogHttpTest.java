package io.github.fatmii.nacoswebconfig.mvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Abrupt client disconnect must not produce ERROR logs from this module or Spring web MVC while
 * draining heartbeats to a dead SSE socket.
 */
@SpringBootTest(
        classes = WebConfigStreamHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "nacos-web-config.stream.heartbeat=50ms")
class WebConfigDisconnectLogHttpTest {
    @LocalServerPort int port;

    @Test
    void abruptDisconnectDoesNotEmitErrorLogs() throws Exception {
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        root.addAppender(appender);
        try {
            var client = HttpClient.newHttpClient();
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode());
            try (var reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertEquals("event:snapshot", reader.readLine().replace(" ", ""));
            }
            // Give heartbeats a chance to hit the closed socket.
            TimeUnit.MILLISECONDS.sleep(300);

            var errors = appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR))
                    .filter(event -> {
                        var name = event.getLoggerName();
                        return name.startsWith("io.github.fatmii.nacoswebconfig")
                                || name.startsWith("org.springframework.web")
                                || name.contains("dispatcherServlet")
                                || name.startsWith("org.apache.catalina");
                    })
                    .toList();
            assertTrue(errors.isEmpty(), () -> "unexpected ERROR logs: " + errors.stream()
                    .map(event -> event.getLoggerName() + " " + event.getFormattedMessage())
                    .toList());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }
}
