package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.work.api.PocketHiveWorkerFunction;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerInvocationTest {

    @Test
    void preservesEarlierAuthorsAndStampsNewStepWithExecutingIdentity() throws Exception {
        WorkerDefinition definition = new WorkerDefinition(
            "processorWorker", PocketHiveWorkerFunction.class, WorkerInputType.RABBITMQ,
            "processor", WorkIoBindings.of("in.queue", "out.queue", "exchange"),
            Void.class, WorkInputConfig.class, WorkOutputConfig.class, WorkerOutputType.RABBITMQ,
            "Processor", Set.of(WorkerCapability.MESSAGE_DRIVEN));
        WorkerState state = new WorkerState(definition);
        state.updateConfig(null, false, true);
        var factory = new DefaultWorkerContextFactory(
            type -> { throw new IllegalStateException("Unexpected bean lookup"); },
            new ControlPlaneIdentity("executing-swarm", definition.role(), "processor-1"));
        PocketHiveWorkerFunction worker = (input, context) -> input.addStepPayload("processed");
        var invocation = new WorkerInvocation(worker, factory, definition, state, List.of());
        WorkerInfo producer = new WorkerInfo("generator", "origin-swarm", "generator-1", null, null);
        WorkItem input = WorkItem.text(producer, "original")
            .header("swarmId", producer.swarmId())
            .header("instanceId", producer.instanceId())
            .header("correlationId", "work-correlation")
            .build();

        WorkItem result = invocation.invoke(input);

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps()).first().isEqualTo(input.steps().iterator().next());
        assertThat(result.stepHeaders())
            .containsEntry(WorkItem.STEP_SERVICE_HEADER, definition.role())
            .containsEntry(WorkItem.STEP_INSTANCE_HEADER, "processor-1");
        assertThat(result.asString()).isEqualTo("processed");
        assertThat(result.headers()).isEqualTo(input.headers());
        assertThat(input.stepHeaders())
            .containsEntry(WorkItem.STEP_SERVICE_HEADER, producer.role())
            .containsEntry(WorkItem.STEP_INSTANCE_HEADER, producer.instanceId());
    }
}
