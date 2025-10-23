package io.pockethive.postprocessor;

import io.pockethive.worker.sdk.metrics.PrometheusPushGatewayProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableRabbit
@SpringBootApplication
@EnableConfigurationProperties(PrometheusPushGatewayProperties.class)
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
