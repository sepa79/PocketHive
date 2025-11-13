package io.pockethive.payload.generator;

import io.pebbletemplates.pebble.PebbleEngine;
import io.pockethive.payload.generator.config.PayloadGeneratorProperties;
import io.pockethive.payload.generator.runtime.PayloadTemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PayloadGeneratorConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PayloadGeneratorConfiguration.class);

    @Bean
    PayloadTemplateRenderer payloadTemplateRenderer(PebbleEngine engine, PayloadGeneratorProperties properties) {
        PayloadTemplateRenderer renderer = new PayloadTemplateRenderer(engine, properties.getTemplate());
        log.info(
            "payloadTemplateRenderer bean initialized (defaultBodyLength={}, headerTemplateCount={})",
            properties.getTemplate().getBody() == null ? 0 : properties.getTemplate().getBody().length(),
            properties.getTemplate().getHeaders() == null ? 0 : properties.getTemplate().getHeaders().size()
        );
        return renderer;
    }
}
