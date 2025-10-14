package io.pockethive.examples.starter.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Minimal Spring Boot entrypoint showing how to bootstrap a processor worker service.
 */
@EnableRabbit
@EnableScheduling
@SpringBootApplication
public class ProcessorWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(ProcessorWorkerApplication.class, args);
  }
}
