package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.orchestrator.domain.SwarmTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;

@Configuration
public class TemplateConfiguration {
    @Bean
    public SwarmTemplate swarmTemplate() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("templates/rest-swarm.yml")) {
            return mapper.readValue(in, SwarmTemplate.class);
        }
    }
}
