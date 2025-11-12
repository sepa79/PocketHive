package io.pockethive.worker.plugin.host;

import java.util.List;

public record PluginDescriptor(
    String pluginClass,
    String role,
    String version,
    List<String> capabilities,
    String configPrefix,
    String defaultConfig
) {
    public PluginDescriptor {
        if (capabilities == null) {
            capabilities = List.of();
        }
    }
}
