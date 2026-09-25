package io.pockethive.worker.sdk.input.message;

import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.worker.sdk.input.WorkMessageDispatcher;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Responsibility: submit decoded Work through bounded executor admission and report accepted-task failures.
 * Must not: settle broker deliveries, dispatch inline, retry accepted work or publish results separately.
 * Contract: RESP-WORK-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-transport.
 */
final class MessageWorkExecution implements WorkDeliveryHandler, AutoCloseable {
    private final Logger log;
    private final String displayName;
    private final WorkerDefinition workerDefinition;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final io.pockethive.controlplane.ControlPlaneIdentity identity;
    private final WorkMessageDispatcher dispatcher;
    private final Consumer<Exception> dispatchErrorHandler;
    private final boolean emitWorkErrorAlerts;
    private final MessageWorkExecutor workExecutor;

    MessageWorkExecution(MessageWorkInputBuilder builder) {
        this.log = builder.log;
        this.displayName = builder.displayName;
        this.workerDefinition = builder.workerDefinition;
        this.controlPlaneRuntime = builder.controlPlaneRuntime;
        this.identity = builder.identity;
        this.dispatcher = builder.dispatcher;
        this.dispatchErrorHandler = builder.dispatchErrorHandler;
        this.emitWorkErrorAlerts = builder.emitWorkErrorAlerts;
        this.workExecutor = new MessageWorkExecutor(workerDefinition.beanName());
    }

    void setMaxInFlight(int configured) { workExecutor.setMaxInFlight(configured); }
    void resume() { workExecutor.resume(); }
    void pause() { workExecutor.pause(); }
    @Override public void close() { workExecutor.close(); }

    @Override
    public void onWork(WorkItem workItem) {
        workExecutor.execute(() -> dispatch(workItem));
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

    private void dispatch(WorkItem workItem) {
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
