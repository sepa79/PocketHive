package io.pockethive.observability;

import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration
@ConditionalOnProperty(prefix = "pockethive.release", name = "version")
public class PocketHiveInfoContributorAutoConfiguration {

  @Bean
  InfoContributor pocketHiveInfoContributor(Environment environment) {
    String serviceName = environment.getRequiredProperty("spring.application.name");
    String releaseVersion = environment.getRequiredProperty("pockethive.release.version");
    return (Info.Builder builder) -> builder.withDetail("pockethive", Map.of(
        "service", serviceName,
        "version", releaseVersion));
  }
}
