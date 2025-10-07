package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.WorkerType;
import org.junit.jupiter.api.Test;

class DefaultWorkerContextFactoryTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        Object.class,
        WorkerType.MESSAGE,
        "role",
        "in.queue",
        "out.queue",
        Void.class
    );

    private final WorkerState state = new WorkerState(DEFINITION);
    private final DefaultWorkerContextFactory factory = new DefaultWorkerContextFactory(
        type -> {
            throw new IllegalStateException("No bean registered for " + type.getName());
        },
        new SimpleMeterRegistry(),
        ObservationRegistry.create()
    );

    @Test
    void generatesObservabilityContextWhenMissing() {
        WorkMessage message = WorkMessage.text("payload")
            .header("swarmId", "swarm-1")
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isNotNull();
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-1");
    }

    @Test
    void fillsMissingObservabilityFieldsAndReusesContext() {
        ObservabilityContext inbound = new ObservabilityContext();
        inbound.setTraceId("");
        inbound.setSwarmId(null);

        WorkMessage message = WorkMessage.text("payload")
            .header("swarmId", "swarm-2")
            .observabilityContext(inbound)
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isSameAs(inbound);
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-2");
    }
}
