package io.pockethive.payload.generator.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.config.CanonicalWorkerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pockethive.workers.payload-generator")
public class PayloadGeneratorProperties extends CanonicalWorkerProperties<PayloadGeneratorConfig> {

    private final ObjectMapper mapper;

    public PayloadGeneratorProperties(ObjectMapper mapper) {
        super("payload-generator", PayloadGeneratorConfig.class, mapper);
        this.mapper = mapper;
    }

    @JsonIgnore
    public PayloadGeneratorConfig defaultConfig() {
        return toConfig(mapper).orElseGet(PayloadGeneratorConfig::new);
    }
}
