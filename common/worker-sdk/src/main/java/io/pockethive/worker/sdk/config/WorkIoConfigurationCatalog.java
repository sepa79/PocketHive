package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.WorkIoType;
import io.pockethive.work.config.WorkIoTypeParser;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import java.util.Arrays;
import java.util.stream.Stream;
import io.pockethive.work.config.binding.*;
import java.util.List;

/**
 * Responsibility: select the unique adapter-owned startup binding descriptor for each IO direction.
 * Must not: define adapter defaults, parse settings, project physical names or create transports.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
public final class WorkIoConfigurationCatalog {
    private final List<WorkInputConfigProvider> inputs;
    private final List<WorkOutputConfigProvider> outputs;

    public WorkIoConfigurationCatalog(List<WorkInputConfigProvider> inputs, List<WorkOutputConfigProvider> outputs) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
    }

    public WorkIoType inputType(String value) {
        return WorkIoTypeParser.parse(value, Stream.concat(Arrays.stream(WorkerInputType.values()),
            inputs.stream().map(WorkInputConfigProvider::type)).toList());
    }

    public WorkIoType outputType(String value) {
        return WorkIoTypeParser.parse(value, Stream.concat(Arrays.stream(WorkerOutputType.values()),
            outputs.stream().map(WorkOutputConfigProvider::type)).toList());
    }

    public Class<? extends WorkInputConfig> inputClass(WorkIoType type) {
        var matches = inputs.stream().filter(provider -> provider.type().equals(type)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one input configuration provider for " + type + ", found " + matches.size());
        return matches.getFirst().configType();
    }

    public Class<? extends WorkOutputConfig> outputClass(WorkIoType type) {
        var matches = outputs.stream().filter(provider -> provider.type().equals(type)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Expected one output configuration provider for " + type + ", found " + matches.size());
        return matches.getFirst().configType();
    }
}
