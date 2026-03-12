package io.pockethive.networkproxy.infra.haproxy;

import io.pockethive.networkproxy.app.HaproxyAdminClient;
import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class HaproxyConfigClient implements HaproxyAdminClient {

    private final Path configFile;

    public HaproxyConfigClient(NetworkProxyManagerProperties properties) {
        this.configFile = Path.of(requireText(
            properties.getHaproxy().getConfigFile(),
            "pockethive.network-proxy-manager.haproxy.config-file"));
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
        Files.writeString(tempFile, renderConfig(sortedRoutes), StandardCharsets.UTF_8);
        Files.move(tempFile, configFile,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
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
