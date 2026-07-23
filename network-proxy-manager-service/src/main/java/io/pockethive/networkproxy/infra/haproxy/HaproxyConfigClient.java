package io.pockethive.networkproxy.infra.haproxy;

import io.pockethive.networkproxy.app.HaproxyAdminClient;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HaproxyConfigClient implements HaproxyAdminClient {

    private final Path configFile;
    private final Path appliedHashFile;
    private final Duration applyTimeout;
    private final Duration applyPollInterval;

    public HaproxyConfigClient(NetworkProxyManagerProperties properties) {
        this.configFile = Path.of(requireText(
            properties.getHaproxy().getConfigFile(),
            "pockethive.network-proxy-manager.haproxy.config-file"));
        this.appliedHashFile = configFile.resolveSibling(configFile.getFileName() + ".applied.sha256");
        this.applyTimeout = requirePositive(
            properties.getHaproxy().getApplyTimeout(),
            "pockethive.network-proxy-manager.haproxy.apply-timeout");
        this.applyPollInterval = requirePositive(
            properties.getHaproxy().getApplyPollInterval(),
            "pockethive.network-proxy-manager.haproxy.apply-poll-interval");
        if (applyPollInterval.toMillis() < 1) {
            throw new IllegalStateException(
                "pockethive.network-proxy-manager.haproxy.apply-poll-interval must be at least 1 ms");
        }
    }

    @Override
    public synchronized void applyRoutes(List<RouteRecord> routes) throws Exception {
        List<RouteRecord> sortedRoutes = routes == null
            ? List.of()
            : routes.stream().sorted(Comparator.comparing(RouteRecord::routeId)).toList();
        Path parent = configFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tempFile = configFile.resolveSibling(configFile.getFileName() + ".tmp");
        byte[] desiredConfig = renderConfig(sortedRoutes).getBytes(StandardCharsets.UTF_8);
        Files.write(tempFile, desiredConfig);
        Files.move(tempFile, configFile,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
        waitUntilApplied(sha256(desiredConfig));
    }

    private void waitUntilApplied(String desiredHash) throws Exception {
        long deadline = System.nanoTime() + applyTimeout.toNanos();
        String lastAppliedHash = "missing";
        do {
            if (Files.exists(appliedHashFile)) {
                lastAppliedHash = Files.readString(appliedHashFile, StandardCharsets.UTF_8).trim();
                if (desiredHash.equals(lastAppliedHash)) {
                    return;
                }
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(applyPollInterval.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while waiting for HAProxy to apply config SHA-256 " + desiredHash,
                    interrupted);
            }
        } while (true);
        throw new IllegalStateException(
            "HAProxy did not apply config SHA-256 " + desiredHash
                + " within " + applyTimeout
                + "; last applied SHA-256=" + lastAppliedHash);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String renderConfig(List<RouteRecord> routes) {
        StringBuilder config = new StringBuilder();
        config.append("global\n");
        config.append("  log stdout format raw local0\n");
        config.append("  maxconn 4096\n");
        config.append("  stats socket /var/run/haproxy/admin.sock mode 660 level admin\n");
        config.append('\n');
        config.append("defaults\n");
        config.append("  mode tcp\n");
        config.append("  log global\n");
        config.append("  timeout connect 5s\n");
        config.append("  timeout client 60s\n");
        config.append("  timeout server 60s\n");
        config.append("  option dontlognull\n");
        config.append('\n');
        config.append("frontend healthcheck\n");
        config.append("  mode http\n");
        config.append("  bind *:8404\n");
        config.append("  http-request return status 200 content-type text/plain string ok if { path /healthz }\n");
        config.append("  stats enable\n");
        config.append("  stats uri /stats\n");
        config.append('\n');
        for (RouteRecord route : routes) {
            String id = sanitize(route.routeId());
            config.append("frontend ingress_").append(id).append('\n');
            config.append("  mode tcp\n");
            config.append("  bind ").append(route.bindAddress()).append('\n');
            config.append("  default_backend upstream_").append(id).append('\n');
            config.append('\n');
            config.append("backend upstream_").append(id).append('\n');
            config.append("  mode tcp\n");
            config.append("  server toxiproxy_").append(id).append(' ')
                .append(route.backendAddress()).append(" check\n");
            config.append('\n');
        }
        return config.toString();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(field + " must be positive");
        }
        return value;
    }

    private static String sanitize(String value) {
        StringBuilder sanitized = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if ((ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')) {
                sanitized.append(Character.toLowerCase(ch));
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.toString();
    }
}
