package io.pockethive.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Canonical default JSON mapper for PocketHive services. */
@AutoConfiguration
public class PocketHiveJacksonAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean(ObjectMapper.class)
  ObjectMapper pocketHiveObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
