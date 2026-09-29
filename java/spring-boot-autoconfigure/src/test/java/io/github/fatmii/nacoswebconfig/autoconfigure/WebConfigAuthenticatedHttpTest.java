package io.github.fatmii.nacoswebconfig.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fatmii.nacoswebconfig.core.ConfigRef;
import io.github.fatmii.nacoswebconfig.core.ConfigRuntime;
import io.github.fatmii.nacoswebconfig.core.ConfigSink;
import io.github.fatmii.nacoswebconfig.core.ConfigSource;
import io.github.fatmii.nacoswebconfig.core.ExposureDefinition;
import io.github.fatmii.nacoswebconfig.core.SourceEvent;
import io.github.fatmii.nacoswebconfig.core.Watch;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

@SpringBootTest(
        classes = WebConfigAuthenticatedHttpTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nacos-web-config.enabled=true",
            "nacos-web-config.access=authenticated",
            "nacos-web-config.exposures.ui.data-id=app-web.public.json"
        })
class WebConfigAuthenticatedHttpTest {
    private static final ConfigRef UI = new ConfigRef("DEFAULT_GROUP", "app-web.public.json");
    @LocalServerPort int port;

    @Test
    void authenticatedAccessRejectsAnonymousRequest() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();
        var response = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            assertEquals(401, response.statusCode());
            var json = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"code\":\"UNAUTHENTICATED\""));
            assertTrue(!json.contains("app-web.public.json"));
        }
    }

    @Test
    void authenticatedAccessUsesHostPrincipalToOpenStream() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/_web-config/v1/stream?key=ui"))
                .header("Accept", "text/event-stream")
                .header("X-Test-Principal", "alice")
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
        @Bean
        ConfigRuntime configRuntime() {
            return ConfigRuntime.start(
                    List.of(new ExposureDefinition("ui", UI, 65_536)), new TestSource());
        }

        @Bean
        FilterRegistrationBean<Filter> hostAuthentication() {
            var registration = new FilterRegistrationBean<Filter>();
            registration.setFilter((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                if (!"alice".equals(http.getHeader("X-Test-Principal"))) {
                    chain.doFilter(request, response);
                    return;
                }
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override
                    public Principal getUserPrincipal() {
                        return () -> "alice";
                    }
                }, response);
            });
            registration.addUrlPatterns("/_web-config/*");
            return registration;
        }
    }

    static final class TestSource implements ConfigSource {
        @Override
        public Watch watch(Set<ConfigRef> refs, ConfigSink sink) {
            sink.accept(new SourceEvent.Value(UI, "{\"theme\":\"dark\"}"));
            return () -> {};
        }

        @Override public void close() {}
    }
}
