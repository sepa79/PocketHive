package io.pockethive.generator;

import io.pockethive.worker.plugin.api.PocketHiveWorkerExtension;
import org.pf4j.Extension;

@Extension
public class GeneratorWorkerPlugin implements PocketHiveWorkerExtension {
    @Override
    public String role() {
        return "generator";
    }

    @Override
    public Class<?>[] configurationClasses() {
        return new Class<?>[]{Application.class};
    }
}
