package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link WorkOutput} implementations keyed by worker bean name.
 * <p>
 * Responsibility: retain selected Work outputs and delegate publication to the registered instance.
 * Must not: choose by ordering, suppress missing factories or independently reopen adapter selection at dispatch.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public final class WorkOutputRegistry {

    private final Map<String, WorkOutput> outputs = new ConcurrentHashMap<>();

    public void register(WorkerDefinition definition, WorkOutput output) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(output, "output");
        outputs.put(definition.beanName(), output);
    }

    public void publish(WorkItem item, WorkerDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(item, "item");
        WorkOutput output = outputs.get(definition.beanName());
        if (output != null) {
            output.publish(item, definition);
        }
    }

    public WorkOutput get(String beanName) {
        return outputs.get(beanName);
    }

    public int registeredCount() {
        return outputs.size();
    }
}
