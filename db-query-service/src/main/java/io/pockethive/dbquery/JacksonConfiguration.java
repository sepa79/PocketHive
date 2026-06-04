package io.pockethive.dbquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class JacksonConfiguration {

  @Bean
  ObjectMapper objectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }
}
