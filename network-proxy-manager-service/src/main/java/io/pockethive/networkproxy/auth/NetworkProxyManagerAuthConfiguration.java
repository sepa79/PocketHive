package io.pockethive.networkproxy.auth;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NetworkProxyManagerAuthConfiguration {
    @Bean
    public AuthServiceClient authServiceClient(NetworkProxyManagerAuthProperties properties) {
        return new AuthServiceClient(
            properties.getServiceUrl(),
            properties.getConnectTimeout(),
            properties.getReadTimeout()
        );
    }

    @Bean
    public AuthServiceServiceTokenProvider networkProxyManagerServiceTokenProvider(
        AuthServiceClient authServiceClient,
        NetworkProxyManagerAuthProperties properties
    ) {
        String serviceName = requireText(properties.getServicePrincipal().getName(), "pockethive.auth.service-principal.name");
        String serviceSecret = requireText(properties.getServicePrincipal().getSecret(), "pockethive.auth.service-principal.secret");
        return new AuthServiceServiceTokenProvider(authServiceClient, serviceName, serviceSecret);
    }

    private static String requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be null or blank");
        }
        return value.trim();
    }
}
