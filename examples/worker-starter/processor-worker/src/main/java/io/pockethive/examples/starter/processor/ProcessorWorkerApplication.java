package io.pockethive.examples.starter.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.rabbit.annotation.EnableRabbit;

/**
 * Minimal Spring Boot entrypoint showing how to bootstrap a processor worker service.
 */
@EnableRabbit
@SpringBootApplication
public class ProcessorWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProcessorWorkerApplication.class, args);
  }
}
