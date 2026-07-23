package io.pockethive.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PocketHiveJacksonAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(PocketHiveJacksonAutoConfiguration.class));

  @Test
  void providesCanonicalDefaultMapper() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
  }

  @Test
  void preservesExplicitServiceMapper() {
    contextRunner.withUserConfiguration(ServiceMapperConfiguration.class).run(context -> {
      assertThat(context).hasSingleBean(ObjectMapper.class);
      assertThat(context.getBean(ObjectMapper.class))
          .isSameAs(context.getBean("serviceMapper"));
    });
  }

  @Configuration(proxyBeanMethods = false)
  static class ServiceMapperConfiguration {

    @Bean
    ObjectMapper serviceMapper() {
      return new ObjectMapper();
    }
  }
}
