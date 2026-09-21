package io.pockethive.worker.sdk.input.message;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.worker.sdk.input.WorkMessageDispatcher;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Responsibility: dispatch decoded Work input with the existing synchronous/asynchronous error policy.
 * Must not: settle Rabbit deliveries, publish results separately or change the established dispatch or callback-return behavior.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
final class MessageWorkExecution implements WorkDeliveryHandler {
    private final Logger log;
    private final String displayName;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final io.pockethive.controlplane.ControlPlaneIdentity identity;
    private final WorkMessageDispatcher dispatcher;
    private final Consumer<Exception> dispatchErrorHandler;
    private final boolean emitWorkErrorAlerts;
    private final AtomicInteger maxInFlight = new AtomicInteger(1);
    private volatile ThreadPoolExecutor workExecutor;
    private final Object executorLock = new Object();

    MessageWorkExecution(MessageWorkInputBuilder builder) {
        this.log = builder.log;
        this.displayName = builder.displayName;
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.identity = builder.identity;
        this.dispatcher = builder.dispatcher;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
        this.emitWorkErrorAlerts = builder.emitWorkErrorAlerts;
    }

    void setMaxInFlight(int configured) {
        int resolved = configured <= 1 ? 1 : configured;
        int previous = maxInFlight.getAndSet(resolved);
        if (resolved <= 1) {
            // No async dispatch required; keep executor (if any) but ensure it does not grow.
            ThreadPoolExecutor executor = workExecutor;
            if (executor != null) {
                executor.setCorePoolSize(1);
                executor.setMaximumPoolSize(1);
            }
            return;
        }
        synchronized (executorLock) {
            ThreadPoolExecutor executor = workExecutor;
            if (executor == null) {
                workExecutor = createExecutor(resolved);
            } else if (resolved != previous) {
                executor.setCorePoolSize(resolved);
                executor.setMaximumPoolSize(resolved);
            }
        }
    }

    private ThreadPoolExecutor createExecutor(int max) {
        SynchronousQueue<Runnable> queue = new SynchronousQueue<>();
	        ThreadFactory threadFactory = runnable -> {
	            Thread thread = new Thread(runnable);
	            thread.setName("ph-worker-" + workerDefinition.beanName() + "-exec-" + thread.threadId());
	            thread.setDaemon(true);
	            return thread;
	        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            max,
            max,
            60L,
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            (task, pool) -> {
                try {
                    pool.getQueue().put(task);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Interrupted while waiting for worker executor slot", ex);
                }
            }
        );
        // Keep core threads alive so per-thread resources (e.g. HTTP clients) can be reused.
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    @Override
    public void onWork(WorkItem workItem) {
        ThreadPoolExecutor executor = workExecutor;
        int currentMax = maxInFlight.get();
        if (executor == null || currentMax <= 1) {
            // Preserve existing synchronous behaviour when no concurrency cap is configured.
            dispatchSynchronously(workItem);
            return;
        }
        try {
            executor.execute(() -> dispatchSynchronously(workItem));
        } catch (RejectedExecutionException ex) {
            log.warn("{} async dispatch rejected; falling back to synchronous processing", displayName, ex);
            if (emitWorkErrorAlerts) {
                try {
                    controlPlaneRuntime.publishWorkError(workerDefinition.beanName(), workItem, ex);
                } catch (Exception publishFailure) {
                    log.warn("{} failed to publish async-dispatch rejection alert", displayName, publishFailure);
                }
            }
            reportDispatchFailure(ex);
            dispatchSynchronously(workItem);
        }
    }

    @Override
    public void onDecodeFailure(byte[] body, Exception ex) {
        if (emitWorkErrorAlerts) {
            try {
                WorkItem fallback = decodeFailureItem(body);
                controlPlaneRuntime.publishWorkError(workerDefinition.beanName(), fallback, ex);
            } catch (Exception publishFailure) {
                log.warn("{} failed to publish decode error alert", displayName, publishFailure);
            }
        }
        reportDispatchFailure(ex);
    }

    // Preserve the existing diagnostic envelope for invalid input; it is never dispatched.
    private WorkItem decodeFailureItem(byte[] body) {
        String payload;
        try {
            payload = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            payload = "";
        }
        WorkerInfo info = new WorkerInfo(
            workerDefinition.role(),
            identity.swarmId(),
            identity.instanceId(),
            null,
            null);
        return WorkItem.text(info, payload).build();
    }

    private void dispatchSynchronously(WorkItem workItem) {
        try {
            dispatcher.dispatch(workItem);
        } catch (Exception ex) {
            logWorkFailure(workItem, ex);
            if (emitWorkErrorAlerts) {
                try {
                    controlPlaneRuntime.publishWorkError(workerDefinition.beanName(), workItem, ex);
                } catch (Exception publishFailure) {
                    log.warn("{} failed to publish work error alert", displayName, publishFailure);
                }
            }
            reportDispatchFailure(ex);
        }
    }

    private void reportDispatchFailure(Exception ex) {
        try {
            dispatchErrorHandler.accept(ex);
        } catch (Exception handlerFailure) {
            log.warn("{} dispatch error handler failed", displayName, handlerFailure);
        }
    }

    private void logWorkFailure(WorkItem workItem, Exception ex) {
        if (!log.isWarnEnabled()) {
            return;
        }
        if (workItem == null) {
            log.warn("{} failed to process work item (workItem=null)", displayName, ex);
            return;
        }
        Object messageId = workItem.headers().get("message-id");
        Object callId = workItem.headers().get("x-ph-call-id");
        Object correlationId = workItem.headers().get("correlationId");
        Object idempotencyKey = workItem.headers().get("idempotencyKey");
        String traceId = workItem.observabilityContext()
            .map(ObservabilityContext::getTraceId)
            .orElse(null);
        log.warn("{} failed to process work item swarmId={} role={} instance={} messageId={} callId={} correlationId={} idempotencyKey={} traceId={}",
            displayName,
            identity != null ? identity.swarmId() : null,
            identity != null ? identity.role() : null,
            identity != null ? identity.instanceId() : null,
            messageId != null ? String.valueOf(messageId) : null,
            callId != null ? String.valueOf(callId) : null,
            correlationId != null ? String.valueOf(correlationId) : null,
            idempotencyKey != null ? String.valueOf(idempotencyKey) : null,
            (traceId != null && !traceId.isBlank()) ? traceId : null,
            ex);
    }

}
