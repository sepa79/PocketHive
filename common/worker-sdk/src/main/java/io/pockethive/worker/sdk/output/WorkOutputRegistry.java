package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link WorkOutput} implementations keyed by worker bean name.
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
