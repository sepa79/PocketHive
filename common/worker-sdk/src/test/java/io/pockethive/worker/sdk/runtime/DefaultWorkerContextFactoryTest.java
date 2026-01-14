package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import org.junit.jupiter.api.Test;
import java.util.Set;

class DefaultWorkerContextFactoryTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        Object.class,
        WorkerInputType.RABBITMQ,
        "role",
        WorkIoBindings.of("in.queue", "out.queue", "exchange.hive"),
        Void.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.RABBITMQ,
        "Test worker",
        Set.of(WorkerCapability.MESSAGE_DRIVEN)
    );

    private final WorkerState state = new WorkerState(DEFINITION);
    private final DefaultWorkerContextFactory factory = new DefaultWorkerContextFactory(
        type -> {
            throw new IllegalStateException("No bean registered for " + type.getName());
        },
        new SimpleMeterRegistry(),
        ObservationRegistry.create(),
        new ControlPlaneIdentity("swarm-config", "swarm-inst-config", DEFINITION.role(), "instance-config")
    );

    @Test
    void generatesObservabilityContextWhenMissing() {
        WorkItem message = WorkItem.text("payload")
            .header("swarmId", "swarm-1")
            .header("swarmInstanceId", "swarm-inst-1")
            .header("instanceId", "instance-1")
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isNotNull();
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-1");
        assertThat(context.info().instanceId()).isEqualTo("instance-1");
    }

    @Test
    void fillsMissingObservabilityFieldsAndReusesContext() {
        ObservabilityContext inbound = new ObservabilityContext();
        inbound.setTraceId("");
        inbound.setSwarmId(null);

        WorkItem message = WorkItem.text("payload")
            .header("swarmId", "swarm-2")
            .header("swarmInstanceId", "swarm-inst-2")
            .header("instanceId", "instance-2")
            .observabilityContext(inbound)
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isSameAs(inbound);
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-2");
    }

    @Test
    void fallsBackToConfiguredIdentityWhenHeadersMissing() {
        WorkItem message = WorkItem.text("payload").build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        assertThat(context.info().swarmId()).isEqualTo("swarm-config");
        assertThat(context.info().instanceId()).isEqualTo("instance-config");
    }

    @Test
    void failsWhenNoHeadersOrConfiguredIdentity() {
        DefaultWorkerContextFactory noIdentityFactory = new DefaultWorkerContextFactory(
            type -> {
                throw new IllegalStateException("No bean registered for " + type.getName());
            },
            new SimpleMeterRegistry(),
            ObservationRegistry.create()
        );

        WorkItem message = WorkItem.text("payload").build();

        assertThatThrownBy(() -> noIdentityFactory.createContext(DEFINITION, state, message))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("swarmId");
    }
}
