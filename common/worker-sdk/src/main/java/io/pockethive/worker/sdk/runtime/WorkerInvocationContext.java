package io.pockethive.worker.sdk.runtime;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable invocation state shared across worker interceptors.
 * <p>
 * Responsibility: carry one invocation's item, worker metadata and context through interceptors.
 * Must not: reimplement service business logic or introduce a second output publication for the same result.
 * Contract: RESP-WORK-INVOCATION — docs/architecture/runtime-responsibilities.md#resp-work-invocation.
 */
public final class WorkerInvocationContext {

    private WorkItem message;
    private final WorkerDefinition definition;
    private final WorkerState state;
    private final WorkerContext workerContext;
    private final Map<String, Object> attributes = new HashMap<>();

    WorkerInvocationContext(WorkerDefinition definition, WorkerState state, WorkerContext workerContext, WorkItem message) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.state = Objects.requireNonNull(state, "state");
        this.workerContext = Objects.requireNonNull(workerContext, "workerContext");
        this.message = Objects.requireNonNull(message, "message");
    }

    public WorkItem message() {
        return message;
    }

    public void message(WorkItem message) {
        this.message = Objects.requireNonNull(message, "message");
    }

    public WorkerDefinition definition() {
        return definition;
    }

    public WorkerState state() {
        return state;
    }

    public WorkerContext workerContext() {
        return workerContext;
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
