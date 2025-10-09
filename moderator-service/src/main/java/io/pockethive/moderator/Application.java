package io.pockethive.moderator;

import io.pockethive.Topology;
import io.pockethive.util.BeeNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@EnableRabbit
@SpringBootApplication
@EnableConfigurationProperties
public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) {
    String beeName = BeeNameGenerator.generate("moderator", Topology.SWARM_ID);
    System.setProperty("bee.name", beeName);
    log.info("Bee name: {}", beeName);
    SpringApplication.run(Application.class, args);
  }

  @Bean
  InfoContributor beeInfoContributor() {
    return (builder) -> builder.withDetail("beeName", System.getProperty("bee.name"));
  }
}
