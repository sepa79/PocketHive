package io.pockethive.payload.generator;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import io.pockethive.payload.generator.runtime.PayloadTemplateRenderer;
import io.pockethive.payload.generator.runtime.StaticDatasetRecordProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PayloadGeneratorProperties.class)
class PayloadGeneratorConfiguration {

    @Bean
    StaticDatasetRecordProvider staticDatasetRecordProvider(PayloadGeneratorProperties properties) {
        return new StaticDatasetRecordProvider(properties.getDataset());
    }

    @Bean
    PayloadTemplateRenderer payloadTemplateRenderer(PebbleEngine engine, PayloadGeneratorProperties properties) {
        return new PayloadTemplateRenderer(engine, properties.getTemplate());
    }
}
