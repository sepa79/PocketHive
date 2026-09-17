package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.api.WorkerCapability;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
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
    private static final WorkerInfo MESSAGE_INFO = new WorkerInfo("ingress", "swarm", "instance", null, null);

    private final WorkerState state = new WorkerState(DEFINITION);
    private final DefaultWorkerContextFactory factory = new DefaultWorkerContextFactory(
        type -> {
            throw new IllegalStateException("No bean registered for " + type.getName());
        },
        new SimpleMeterRegistry(),
        ObservationRegistry.create(),
        new ControlPlaneIdentity("swarm-config", DEFINITION.role(), "instance-config")
    );

    @Test
    void generatesObservabilityContextWhenMissing() {
        WorkItem message = WorkItem.text(MESSAGE_INFO, "payload")
            .header("swarmId", "swarm-1")
            .header("instanceId", "instance-1")
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isNotNull();
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-config");
        assertThat(context.info().instanceId()).isEqualTo("instance-config");
    }

    @Test
    void fillsMissingObservabilityFieldsAndReusesContext() {
        ObservabilityContext inbound = new ObservabilityContext();
        inbound.setTraceId("");
        inbound.setSwarmId(null);

        WorkItem message = WorkItem.text(MESSAGE_INFO, "payload")
            .header("swarmId", "swarm-2")
            .header("instanceId", "instance-2")
            .observabilityContext(inbound)
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        ObservabilityContext observabilityContext = context.observabilityContext();
        assertThat(observabilityContext).isSameAs(inbound);
        assertThat(observabilityContext.getTraceId()).isNotBlank();
        assertThat(observabilityContext.getHops()).isEmpty();
        assertThat(observabilityContext.getSwarmId()).isEqualTo("swarm-config");
    }

    @Test
    void usesConfiguredIdentityWithoutExplicitMessageHeaders() {
        WorkItem message = WorkItem.text(MESSAGE_INFO, "payload").build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        assertThat(context.info().swarmId()).isEqualTo("swarm-config");
        assertThat(context.info().instanceId()).isEqualTo("instance-config");
    }

    @Test
    void preservesIncomingTraceContextWithoutUsingItAsExecutingIdentity() {
        ObservabilityContext inbound = new ObservabilityContext();
        inbound.setTraceId("trace-from-producer");
        inbound.setSwarmId("origin-swarm");
        WorkItem message = WorkItem.text(MESSAGE_INFO, "payload")
            .header("swarmId", "origin-swarm")
            .header("instanceId", "origin-instance")
            .observabilityContext(inbound)
            .build();

        WorkerContext context = factory.createContext(DEFINITION, state, message);

        assertThat(context.info().swarmId()).isEqualTo("swarm-config");
        assertThat(context.info().instanceId()).isEqualTo("instance-config");
        assertThat(context.observabilityContext()).isSameAs(inbound);
        assertThat(inbound.getTraceId()).isEqualTo("trace-from-producer");
        assertThat(inbound.getSwarmId()).isEqualTo("origin-swarm");
        assertThat(message.headers()).containsEntry("swarmId", "origin-swarm")
            .containsEntry("instanceId", "origin-instance");
    }

    @Test
    void requiresConfiguredIdentityAtConstruction() {
        assertThatThrownBy(() -> new DefaultWorkerContextFactory(
            type -> { throw new IllegalStateException("Unexpected bean lookup"); },
            new SimpleMeterRegistry(),
            ObservationRegistry.create(),
            null
        )).isInstanceOf(NullPointerException.class).hasMessage("identity");
    }
}
