package io.pockethive.worker.plugin.api;

import org.pf4j.ExtensionPoint;

/**
 * Extension contract implemented by every PocketHive worker plugin so the host can bootstrap
 * the worker runtime inside a PF4J context.
 */
public interface PocketHiveWorkerExtension extends ExtensionPoint {

    String role();

    Class<?>[] configurationClasses();

    default void onStart() {}

    default void onStop() {}
}
