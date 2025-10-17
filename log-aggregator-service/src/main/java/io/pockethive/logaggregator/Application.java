package io.pockethive.logaggregator;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@EnableRabbit
@SpringBootApplication
@EnableConfigurationProperties(LogAggregatorControlPlaneProperties.class)
public class Application {

  public static void main(String[] args){
    SpringApplication.run(Application.class, args);
  }
}
