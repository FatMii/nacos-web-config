package io.github.fatmii.nacoswebconfig.example.secured;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Host that keeps the configuration stream behind the application's own authentication. */
@SpringBootApplication
@RestController
public class SecuredDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecuredDemoApplication.class, args);
    }

    /** An ordinary business endpoint, intentionally unrelated to web-config internals. */
    @GetMapping("/demo")
    String demo() {
        return "nacos-web-config secured demo";
    }

    @Bean
    SecurityFilterChain demoFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults())
                .httpBasic(Customizer.withDefaults())
                // A stream request that is not a page navigation must learn "denied" as a status
                // code; redirecting it to the HTML login page would hide the refusal in a parse error.
                .exceptionHandling(handling -> handling.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new MediaTypeRequestMatcher(MediaType.TEXT_EVENT_STREAM)))
                .build();
    }
}
