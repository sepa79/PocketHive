package io.pockethive.processor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProcessorConfig {
  @Value("${ph.processor.baseUrl:}")
  private String baseUrl;

  @Bean
  public String baseUrl(){
    return baseUrl;
  }
}
