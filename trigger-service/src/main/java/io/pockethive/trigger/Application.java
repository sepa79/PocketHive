package io.pockethive.trigger;

import io.pockethive.controlplane.spring.BeeIdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@EnableRabbit
@SpringBootApplication
public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  ApplicationRunner beeIdentityLogger(BeeIdentityProperties beeIdentityProperties) {
    return args -> log.info("Bee name: {}", beeIdentityProperties.beeName());
  }
}
