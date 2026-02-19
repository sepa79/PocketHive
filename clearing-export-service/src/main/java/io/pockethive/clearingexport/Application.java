package io.pockethive.clearingexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.metrics.PrometheusPushGatewayProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableRabbit
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(PrometheusPushGatewayProperties.class)
public class Application {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
