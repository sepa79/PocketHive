package io.pockethive.orchestrator.auth;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
@EnableConfigurationProperties(OrchestratorAuthProperties.class)
public class OrchestratorAuthConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
    public AuthServiceClient authServiceClient(OrchestratorAuthProperties properties) {
        return new AuthServiceClient(
            properties.getServiceUrl(),
            properties.getConnectTimeout(),
            properties.getReadTimeout()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
    public FilterRegistrationBean<OrchestratorAuthFilter> orchestratorAuthFilter(
        AuthServiceClient authServiceClient,
        OrchestratorAuthorization authorization
    ) {
        FilterRegistrationBean<OrchestratorAuthFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new OrchestratorAuthFilter(authServiceClient, authorization));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    @Bean
    @ConditionalOnProperty(prefix = "pockethive.auth", name = "enabled", havingValue = "true")
    public AuthServiceServiceTokenProvider orchestratorServiceTokenProvider(
        AuthServiceClient authServiceClient,
        OrchestratorAuthProperties properties
    ) {
        String serviceName = requireText(properties.getServicePrincipal().getName(), "pockethive.auth.service-principal.name");
        String serviceSecret = requireText(properties.getServicePrincipal().getSecret(), "pockethive.auth.service-principal.secret");
        return new AuthServiceServiceTokenProvider(authServiceClient, serviceName, serviceSecret);
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank when pockethive.auth.enabled=true");
        }
        return value.trim();
    }
}
