package io.pockethive.scenarios.auth;

import io.pockethive.auth.client.AuthServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(ScenarioManagerAuthProperties.class)
public class ScenarioManagerAuthConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
    public AuthServiceClient authServiceClient(ScenarioManagerAuthProperties properties) {
        return new AuthServiceClient(
            properties.getServiceUrl(),
            properties.getConnectTimeout(),
            properties.getReadTimeout()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<ScenarioManagerAuthFilter> scenarioManagerAuthFilter(
        AuthServiceClient authServiceClient,
        ScenarioManagerAuthorization authorization
    ) {
        FilterRegistrationBean<ScenarioManagerAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new ScenarioManagerAuthFilter(authServiceClient, authorization));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }
}
