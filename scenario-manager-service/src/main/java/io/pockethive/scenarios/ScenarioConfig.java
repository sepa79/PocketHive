package io.pockethive.scenarios;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ScenarioManagerProperties.class)
public class ScenarioConfig {
    @Bean
    MappingJackson2HttpMessageConverter yamlConverter() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(mapper);
        converter.setSupportedMediaTypes(List.of(
                MediaType.valueOf("application/x-yaml"),
                MediaType.valueOf("application/yaml")));
        return converter;
    }

    @Bean
    RestClient orchestratorRestClient(ScenarioManagerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.orchestrator().getConnectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.orchestrator().getReadTimeout().toMillis());
        return RestClient.builder()
                .baseUrl(properties.orchestrator().getBaseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    @Bean
    OrchestratorCapabilitiesClient orchestratorCapabilitiesClient(RestClient orchestratorRestClient) {
        return new RestOrchestratorCapabilitiesClient(orchestratorRestClient);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
