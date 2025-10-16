package io.pockethive.generator;

import io.pockethive.util.BeeNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableRabbit
@SpringBootApplication
@EnableConfigurationProperties
public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) {
    String beeName = BeeNameGenerator.requireConfiguredName();
    log.info("Bee name: {}", beeName);
    SpringApplication.run(Application.class, args);
  }

}
