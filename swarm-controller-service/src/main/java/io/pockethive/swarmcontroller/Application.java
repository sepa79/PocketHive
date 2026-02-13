package io.pockethive.swarmcontroller;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@EnableRabbit
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = {
    "io.pockethive.swarmcontroller.config",
    "io.pockethive.sink.clickhouse"
})
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
