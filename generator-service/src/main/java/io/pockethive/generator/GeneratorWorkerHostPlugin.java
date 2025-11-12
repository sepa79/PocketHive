package io.pockethive.generator;

import io.pockethive.worker.plugin.api.PocketHiveWorkerPlugin;
import org.pf4j.PluginWrapper;

/**
 * PF4J plugin entry point for the generator worker plugin.
 * Keeps the plugin descriptor simple by reusing the shared PocketHive base class.
 */
public class GeneratorWorkerHostPlugin extends PocketHiveWorkerPlugin {

    public GeneratorWorkerHostPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}
