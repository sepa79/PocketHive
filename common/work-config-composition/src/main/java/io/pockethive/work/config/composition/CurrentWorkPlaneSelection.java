package io.pockethive.work.config.composition;

import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.WorkIoTypeParser;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkPlaneSelection;
import java.util.List;
import java.util.function.Function;

/**
 * Responsibility: resolve the explicit deployment selector against the current WorkPlane inventory.
 * Must not: infer a provider from credentials or instantiate adapter resources.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-adapter-selection.
 */
public final class CurrentWorkPlaneSelection {
    private CurrentWorkPlaneSelection() { }
    public static WorkPlaneSelection resolve(Function<String, String> properties) {
        return new WorkPlaneSelection(WorkIoTypeParser.parse(properties.apply(WorkPlaneSelection.PROPERTY),
            List.of(WorkerInputType.RABBITMQ, ArtemisWorkIoType.ARTEMIS)));
    }
}
