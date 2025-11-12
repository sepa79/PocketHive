package io.pockethive.worker.plugin.host.fixture;

import io.pockethive.worker.plugin.api.PocketHiveWorkerExtension;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pf4j.Extension;

@Extension
public class TestWorkerPlugin implements PocketHiveWorkerExtension {

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    public static boolean started() {
        return STARTED.get();
    }

    public static void reset() {
        STARTED.set(false);
    }

    @Override
    public String role() {
        return "test-role";
    }

    @Override
    public Class<?>[] configurationClasses() {
        return new Class<?>[]{TestWorkerPluginConfig.class};
    }

    @Override
    public void onStart() {
        STARTED.set(true);
    }

    @Override
    public void onStop() {
        STARTED.set(false);
    }
}
