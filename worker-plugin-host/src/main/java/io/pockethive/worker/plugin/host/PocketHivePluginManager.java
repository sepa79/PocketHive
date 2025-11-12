package io.pockethive.worker.plugin.host;

import java.nio.file.Path;
import org.pf4j.spring.SpringExtensionFactory;
import org.pf4j.spring.SpringPluginManager;

final class PocketHivePluginManager extends SpringPluginManager {

    PocketHivePluginManager(Path pluginsDir) {
        super(pluginsDir);
    }

    @Override
    protected SpringExtensionFactory createExtensionFactory() {
        return new SpringExtensionFactory(this);
    }
}
