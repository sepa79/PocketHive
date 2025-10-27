package io.pockethive.scenarios;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.pockethive")
public class ScenarioManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScenarioManagerApplication.class, args);
    }
}
