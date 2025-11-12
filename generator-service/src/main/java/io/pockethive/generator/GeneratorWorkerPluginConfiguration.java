package io.pockethive.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@Configuration
@ComponentScan(basePackageClasses = GeneratorWorkerImpl.class)
public class GeneratorWorkerPluginConfiguration {

    @Bean(name = "pockethive.workers.generator")
    @ConfigurationProperties(prefix = "pockethive.workers.generator")
    GeneratorWorkerProperties generatorWorkerProperties(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new GeneratorWorkerProperties(mapper);
    }
}
