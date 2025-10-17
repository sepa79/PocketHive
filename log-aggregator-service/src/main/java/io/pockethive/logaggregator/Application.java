package io.pockethive.logaggregator;

import io.pockethive.Topology;
import io.pockethive.util.BeeNameGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

@EnableRabbit
@SpringBootApplication
public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(Application.class);
    app.addListeners(environmentListener());
    app.run(args);
    log.info("Bee name: {}", System.getProperty("bee.name"));
  }

  private static ApplicationListener<ApplicationEnvironmentPreparedEvent> environmentListener() {
    return event -> {
      ConfigurableEnvironment environment = event.getEnvironment();
      String swarmId = environment.getProperty("pockethive.control-plane.swarm-id");
      if (swarmId == null || swarmId.isBlank()) {
        throw new IllegalStateException(
            "Missing required PocketHive configuration: pockethive.control-plane.swarm-id");
      }
      System.setProperty("POCKETHIVE_CONTROL_PLANE_SWARM_ID", swarmId);
      String beeName = BeeNameGenerator.generate("log-aggregator", Topology.SWARM_ID);
      System.setProperty("bee.name", beeName);
    };
  }

  @Bean
  InfoContributor beeInfoContributor() {
    return (builder) -> builder.withDetail("beeName", System.getProperty("bee.name"));
  }
}
