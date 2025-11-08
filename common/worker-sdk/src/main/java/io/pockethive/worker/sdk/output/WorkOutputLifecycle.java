package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Lifecycle bean that coordinates {@link WorkOutputRegistry} startup by ensuring outputs are initialised
 * together with inputs.
 */
public final class WorkOutputLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkOutputLifecycle.class);

    private final WorkOutputRegistry registry;
    private volatile boolean running;

    public WorkOutputLifecycle(WorkOutputRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        if (log.isInfoEnabled()) {
            log.info("Work output lifecycle started (outputs={})", registry.registeredCount());
        }
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (log.isInfoEnabled()) {
            log.info("Work output lifecycle stopped");
        }
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }
}
