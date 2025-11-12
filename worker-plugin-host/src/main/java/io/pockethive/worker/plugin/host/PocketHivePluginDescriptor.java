package io.pockethive.worker.plugin.host;

import java.util.List;

record PocketHivePluginDescriptor(String role,
                                 String version,
                                 String configPrefix,
                                 String defaultConfig,
                                 List<String> capabilities) {
}
