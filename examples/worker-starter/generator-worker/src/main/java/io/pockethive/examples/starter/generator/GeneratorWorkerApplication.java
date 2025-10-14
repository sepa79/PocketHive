package io.pockethive.examples.starter.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the sample generator worker.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class GeneratorWorkerApplication {

  public static void main(String[] args) {
    SpringApplication.run(GeneratorWorkerApplication.class, args);
  }
}
