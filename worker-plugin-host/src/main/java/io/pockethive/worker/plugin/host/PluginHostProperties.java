package io.pockethive.worker.plugin.host;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.plugin-host")
public class PluginHostProperties {

    private boolean enabled = true;
    private boolean failOnMissingPlugin = true;
    private Path pluginsDir = Path.of("/opt/pockethive/plugins");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOnMissingPlugin() {
        return failOnMissingPlugin;
    }

    public void setFailOnMissingPlugin(boolean failOnMissingPlugin) {
        this.failOnMissingPlugin = failOnMissingPlugin;
    }

    public Path getPluginsDir() {
        return pluginsDir;
    }

    public void setPluginsDir(Path pluginsDir) {
        this.pluginsDir = pluginsDir;
    }
}
