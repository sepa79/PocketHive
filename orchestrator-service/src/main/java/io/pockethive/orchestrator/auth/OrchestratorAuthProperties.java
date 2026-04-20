package io.pockethive.orchestrator.auth;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.auth")
public class OrchestratorAuthProperties {
    private URI serviceUrl = URI.create("http://auth-service:8080");
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(5);
    private ServicePrincipal servicePrincipal = new ServicePrincipal();

    public URI getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(URI serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public ServicePrincipal getServicePrincipal() {
        return servicePrincipal;
    }

    public void setServicePrincipal(ServicePrincipal servicePrincipal) {
        this.servicePrincipal = servicePrincipal == null ? new ServicePrincipal() : servicePrincipal;
    }

    public static class ServicePrincipal {
        private String name;
        private String secret;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }
}
