package io.pockethive.mcp.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class McpDeploymentContractTest {
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();

    @Test
    void localBuildAndComposeUseOneHardenedJavaMcpImage() throws IOException {
        assertThat(text("tools/docker/image-manifest.sh"))
            .contains("register_pockethive_image pockethive-mcp pockethive-mcp pockethive-mcp-service")
            .doesNotContain("tools/pockethive-mcp pockethive-mcp");

        String dockerfile = text("pockethive-mcp-service/Dockerfile.runtime");
        assertThat(dockerfile)
            .contains(".local-jars/pockethive-mcp-service.jar /app/app.jar")
            .contains(".local-jars/pockethive-mcp-service.sbom.json /app/pockethive-mcp-sbom.cdx.json")
            .contains("USER 10001:10001");

        assertThat(text("build-hive.sh"))
            .contains("pockethive-mcp-service.sbom.json");
        assertThat(text("tools/docker/remote-images.sh"))
            .contains("pockethive-mcp-service.sbom.json");
        assertThat(text(".github/workflows/publish-images.yml"))
            .contains("pockethive-mcp-service \\")
            .contains("pockethive-mcp-service.sbom.json");

        String compose = text("docker-compose.yml");
        assertThat(compose)
            .contains("  pockethive-mcp:")
            .contains("image: ${DOCKER_REGISTRY:-}pockethive-mcp:${POCKETHIVE_VERSION:-latest}")
            .contains("PH_MCP_POCKETHIVE_INGRESS: http://localhost:8088")
            .contains("PH_MCP_OWNER_API_BASE: http://ui:8088")
            .contains("PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT: PT2S")
            .contains("read_only: true", "cap_drop:", "- ALL", "pockethive-mcp-state:")
            .doesNotContain("3100:8080");
    }

    @Test
    void everyPublicIngressTransparentlyRoutesMcpAndUploadTraffic() throws IOException {
        for (String path : List.of(
            "ui-v2/nginx.conf",
            "deploy/hiveforge/runtime/nginx.swarm.conf",
            "deploy/hiveforge/runtime/nginx.reduced.conf")) {
            String ingress = text(path);
            assertThat(ingress).as(path)
                .contains("location = /mcp")
                .contains("location ^~ /mcp/uploads/")
                .contains("location = /.well-known/oauth-protected-resource")
                .contains("proxy_pass http://$pockethive_mcp:8080")
                .contains("proxy_buffering off")
                .doesNotContain("rewrite ^/mcp");
            assertThat(ingress.lines()
                .filter(line -> line.trim().equals("proxy_set_header Host $http_host;")))
                .as("%s must preserve the public host and port on every MCP route", path)
                .hasSizeGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void fullIngressesExposeTheCanonicalTcpMockHealthPath() throws IOException {
        for (String path : List.of("ui-v2/nginx.conf", "deploy/hiveforge/runtime/nginx.swarm.conf")) {
            assertThat(text(path)).as(path)
                .contains("location /tcp-mock/")
                .contains("set $tcp_mock_host tcp-mock-server;")
                .contains("rewrite ^/tcp-mock/(.*)$ /$1 break;")
                .contains("proxy_pass http://$tcp_mock_host:8080;");
        }
    }

    @Test
    void hiveForgeReleaseAndStackDeployTheSameMcpImageWithPersistentState() throws IOException {
        assertThat(text("deploy/hiveforge/release-artifact.json"))
            .contains("\"name\": \"pockethive-mcp\"")
            .contains("/pockethive-mcp:{{ release.imageTag }}");
        assertThat(text("deploy/hiveforge/components/stack/ansible/templates/stack-compose.yml.j2"))
            .contains("  pockethive-mcp:")
            .contains("state/pockethive-mcp:/var/lib/pockethive-mcp/state")
            .contains("PH_MCP_POCKETHIVE_INGRESS: {{ pockethive_public_ingress }}")
            .contains("PH_MCP_OWNER_API_BASE: http://ui:8088")
            .contains("PH_MCP_ENVIRONMENT_HEALTH_PROBE_TIMEOUT: PT2S");
        assertThat(text("deploy/hiveforge/components/stack/ansible/swarm-stack.yml"))
            .contains("state/pockethive-mcp");
    }

    private static String text(String relativePath) throws IOException {
        return Files.readString(REPOSITORY.resolve(relativePath));
    }
}
