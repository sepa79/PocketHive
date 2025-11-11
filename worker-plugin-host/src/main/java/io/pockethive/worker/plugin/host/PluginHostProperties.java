package io.pockethive.worker.plugin.host;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pockethive.plugin-host")
public class PluginHostProperties {

    private Path pluginDir = Path.of("/opt/pockethive/plugins");

    public Path getPluginDir() {
        return pluginDir;
    }

    public void setPluginDir(Path pluginDir) {
        this.pluginDir = pluginDir;
    }
}
