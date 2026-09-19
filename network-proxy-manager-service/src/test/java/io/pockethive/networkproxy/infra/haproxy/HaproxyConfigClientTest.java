package io.pockethive.networkproxy.infra.haproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.networkproxy.app.HaproxyAdminClient;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HaproxyConfigClientTest {

    @TempDir
    Path tempDir;

    @Test
    void renderConfigIncludesHealthcheckAndTcpRoutes() {
        String config = HaproxyConfigClient.renderConfig(List.of(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"),
            new HaproxyAdminClient.RouteRecord("sut-b__default", "0.0.0.0:18080", "toxiproxy:28080")));

        assertThat(config).contains("frontend healthcheck");
        assertThat(config).contains("bind *:8404");
        assertThat(config).contains("frontend ingress_sut_a__payments");
        assertThat(config).contains("bind 0.0.0.0:9443");
        assertThat(config).contains("server toxiproxy_sut_a__payments toxiproxy:19443 check");
        assertThat(config).contains("frontend ingress_sut_b__default");
        assertThat(config).contains("server toxiproxy_sut_b__default toxiproxy:28080 check");
    }

    @Test
    void applyWaitsUntilHaproxyConfirmsTheExactConfigDigest() throws Exception {
        Path configFile = tempDir.resolve("haproxy.cfg");
        Path appliedDigestFile = tempDir.resolve("confirmed-digest");
        HaproxyConfigClient client = new HaproxyConfigClient(properties(configFile, appliedDigestFile,
            Duration.ofSeconds(2), Duration.ofMillis(10)));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var apply = executor.submit(() -> {
                client.applyRoutes(List.of(new HaproxyAdminClient.RouteRecord(
                    "sut-a__default", "0.0.0.0:18080", "toxiproxy:28080")));
                return null;
            });

            while (!Files.exists(configFile)) {
                Thread.sleep(10);
            }
            assertThat(apply.isDone()).isFalse();
            Files.writeString(
                appliedDigestFile,
                sha256(Files.readAllBytes(configFile)),
                StandardCharsets.UTF_8);

            apply.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void applyFailsExplicitlyWhenHaproxyDoesNotConfirmTheDesiredDigest() throws Exception {
        Path configFile = tempDir.resolve("haproxy.cfg");
        Path appliedDigestFile = tempDir.resolve("confirmed-digest");
        Files.writeString(
            appliedDigestFile,
            "stale-digest",
            StandardCharsets.UTF_8);
        HaproxyConfigClient client = new HaproxyConfigClient(properties(configFile, appliedDigestFile,
            Duration.ofMillis(100), Duration.ofMillis(10)));

        assertThatThrownBy(() -> client.applyRoutes(List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("HAProxy did not apply config SHA-256")
            .hasMessageContaining("last applied SHA-256=stale-digest");
    }

    private static NetworkProxyManagerProperties properties(
        Path configFile,
        Path appliedDigestFile,
        Duration applyTimeout,
        Duration applyPollInterval) {
        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getHaproxy().setConfigFile(configFile.toString());
        properties.getHaproxy().setAppliedDigestFile(appliedDigestFile.toString());
        properties.getHaproxy().setApplyTimeout(applyTimeout);
        properties.getHaproxy().setApplyPollInterval(applyPollInterval);
        return properties;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
