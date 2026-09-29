package io.github.fatmii.nacoswebconfig.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.config.ConfigService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
        classes = StarterHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.enabled=true",
            "nacos-web-config.access=public",
            "nacos-web-config.source.mode=bean",
            "nacos-web-config.source.bean-name=testConfigService",
            "nacos-web-config.exposures.ui.data-id=app-web.public.json"
        })
class StarterHttpTest {
    @LocalServerPort int port;

    @Test
    void starterAloneProvidesEnabledStreamEndpoint() throws Exception {
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
            assertEquals("event:snapshot", reader.readLine().replace(" ", ""));
            assertTrue(reader.readLine().contains("\"ui\""));
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean("testConfigService")
        ConfigService configService() throws Exception {
            var service = mock(ConfigService.class);
            when(service.getConfigAndSignListener(
                            eq("app-web.public.json"), eq("DEFAULT_GROUP"), anyLong(), any()))
                    .thenReturn("{\"theme\":\"dark\"}");
            return service;
        }
    }
}
