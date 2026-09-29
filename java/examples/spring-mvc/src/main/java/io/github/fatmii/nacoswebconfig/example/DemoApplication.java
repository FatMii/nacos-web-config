package io.github.fatmii.nacoswebconfig.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Minimal host proving that the Starter can be added to an ordinary Spring MVC application. */
@SpringBootApplication
@RestController
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    /** An ordinary business endpoint, intentionally unrelated to web-config internals. */
    @GetMapping("/demo")
    String demo() {
        return "nacos-web-config demo";
    }
}
