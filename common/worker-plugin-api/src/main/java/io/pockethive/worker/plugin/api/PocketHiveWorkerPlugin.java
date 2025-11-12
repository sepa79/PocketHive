package io.pockethive.worker.plugin.api;

import org.pf4j.PluginWrapper;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;

/**
 * Base PF4J plugin that integrates with {@link PocketHiveWorkerExtension} implementations.
 * <p>
 * Worker plugin jars expose their Spring configuration through {@link PocketHiveWorkerExtension};
 * this wrapper simply satisfies PF4J's requirement for a concrete {@code plugin.class} while
 * providing lifecycle hooks for subclasses when needed.
 */
public abstract class PocketHiveWorkerPlugin extends Plugin {

    protected PocketHiveWorkerPlugin(PluginWrapper wrapper) {
        super(wrapper);
    }
}
