package io.pockethive.worker.sdk.input.message;

import io.pockethive.work.api.transport.WorkNotAcceptedException;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Responsibility: admit bounded Work tasks to one reusable thread pool and cancel only waiting admission on pause.
 * Must not: run tasks inline, settle deliveries, retry work or interrupt accepted tasks on stop.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
final class MessageWorkExecutor implements AutoCloseable {
    static final int MIN_IN_FLIGHT = 1;
    private final ThreadPoolExecutor executor;
    private int limit = MIN_IN_FLIGHT;
    private int inFlight;
    private boolean accepting;

    MessageWorkExecutor(String workerName) {
        Objects.requireNonNull(workerName, "workerName");
        // Admission bounds all submitted work. The queue only hands accepted tasks to
        // reusable threads, including the short gap before a completing thread is idle.
        executor = new ThreadPoolExecutor(MIN_IN_FLIGHT, MIN_IN_FLIGHT, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), Thread.ofPlatform().daemon(true)
                .name("ph-worker-" + workerName + "-exec-", 0).factory());
        executor.allowCoreThreadTimeOut(false);
    }

    synchronized void setMaxInFlight(int configured) {
        limit = Math.max(MIN_IN_FLIGHT, configured);
        if (limit > executor.getMaximumPoolSize()) {
            executor.setMaximumPoolSize(limit);
            executor.setCorePoolSize(limit);
        } else {
            executor.setCorePoolSize(limit);
            executor.setMaximumPoolSize(limit);
        }
        notifyAll();
    }

    synchronized void resume() {
        if (executor.isShutdown()) throw new IllegalStateException("Work executor is closed");
        accepting = true;
        notifyAll();
    }

    synchronized void pause() {
        accepting = false;
        notifyAll();
    }

    synchronized void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        while (accepting && inFlight >= limit) {
            try {
                wait();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new WorkNotAcceptedException("Work admission interrupted", failure);
            }
        }
        if (!accepting) throw new WorkNotAcceptedException("Work admission is paused");
        inFlight++;
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    completed();
                }
            });
        } catch (RuntimeException failure) {
            completed();
            throw new WorkNotAcceptedException("Work task was not submitted", failure);
        }
    }

    private synchronized void completed() {
        inFlight--;
        notifyAll();
    }

    @Override public synchronized void close() {
        pause();
        executor.shutdown();
    }
}
