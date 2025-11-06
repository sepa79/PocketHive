package io.pockethive.worker.sdk.input;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Manages the lifecycle of registered {@link WorkInput} instances, starting them when the application
 * context is ready and stopping them on shutdown.
 */
public final class WorkInputLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(WorkInputLifecycle.class);

    private final WorkInputRegistry registry;
    private volatile boolean running;

    public WorkInputLifecycle(WorkInputRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        registry.registrations().forEach(this::startInput);
        running = true;
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        registry.registrations().forEach(this::stopInput);
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
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

    private void startInput(WorkInputRegistry.Registration registration) {
        try {
            registration.input().start();
            log.debug("Started work input for worker {}", registration.definition().beanName());
        } catch (Exception ex) {
            log.warn("Failed to start work input for worker {}", registration.definition().beanName(), ex);
        }
    }

    private void stopInput(WorkInputRegistry.Registration registration) {
        try {
            registration.input().stop();
            log.debug("Stopped work input for worker {}", registration.definition().beanName());
        } catch (Exception ex) {
            log.warn("Failed to stop work input for worker {}", registration.definition().beanName(), ex);
        }
    }
}
