package io.pockethive.payload.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class PayloadGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayloadGeneratorApplication.class, args);
    }
}
