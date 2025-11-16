package io.pockethive.worker.sdk.runtime;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Mutable invocation state shared across worker interceptors.
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
