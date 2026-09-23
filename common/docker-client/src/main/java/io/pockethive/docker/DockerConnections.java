package io.pockethive.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

/**
 * Responsibility: realize Docker daemon configuration and its HTTP client.
 * Must not: select compute mode or maintain lifecycle state.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
final class DockerConnections {
    private DockerConnections() {
    }

    static DefaultDockerClientConfig environment() {
        return DefaultDockerClientConfig.createDefaultConfigBuilder().build();
    }

    static DefaultDockerClientConfig controller(String host, String socketPath) {
        var builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
        builder.withDockerHost(host != null && !host.isBlank() ? host : DockerControllerEnvironment.socketHost(socketPath));
        return builder.build();
    }

    static DockerClient open(DefaultDockerClientConfig config) {
        var http = new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig()).build();
        return DockerClientImpl.getInstance(config, http);
    }
}
