package io.pockethive.processor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.http.ProcessorHttpClient;
import io.pockethive.processor.http.ApacheProcessorHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Responsibility: compose the processor mapper and outbound HTTP client owner.
 * Must not: implement client settings, request execution or worker domain policy.
 * Contract: RESP-PROCESSOR-HTTP-CLIENT — docs/architecture/runtime-responsibilities.md#resp-processor-http-client.
 */
@Configuration
public class ProcessorConfiguration {

    @Bean
    public ProcessorHttpClient processorHttpClient() {
        return new ApacheProcessorHttpClient();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
