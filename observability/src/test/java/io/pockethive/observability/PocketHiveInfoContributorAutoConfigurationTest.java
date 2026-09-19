package io.pockethive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PocketHiveInfoContributorAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(PocketHiveInfoContributorAutoConfiguration.class));

  @Test
  void contributesPocketHiveServiceAndVersionToInfoEndpoint() {
    contextRunner
        .withPropertyValues(
            "spring.application.name=orchestrator-service",
            "pockethive.release.version=0.15.35")
        .run(context -> {
          assertThat(context).hasSingleBean(InfoContributor.class);
          assertThat(contribute(context.getBean(InfoContributor.class)))
              .containsEntry("pockethive", java.util.Map.of(
                  "service", "orchestrator-service",
                  "version", "0.15.35"));
        });
  }

  @Test
  void doesNotRegisterContributorWhenReleaseVersionIsMissing() {
    contextRunner
        .withPropertyValues("spring.application.name=orchestrator-service")
        .run(context -> assertThat(context).doesNotHaveBean(InfoContributor.class));
  }

  private Map<String, Object> contribute(InfoContributor contributor) {
    Info.Builder builder = new Info.Builder();
    contributor.contribute(builder);
    return new LinkedHashMap<>(builder.build().getDetails());
  }
}
