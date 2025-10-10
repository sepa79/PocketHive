package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.worker.sdk.config.WorkerType;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerStatusPublisherTest {

    private static final WorkerDefinition DEFINITION = new WorkerDefinition(
        "testWorker",
        Object.class,
        WorkerType.MESSAGE,
        "role",
        "in.queue",
        "out.queue",
        Void.class
    );

    @Test
    void recordProcessedUpdatesWorkerState() {
        WorkerState state = new WorkerState(DEFINITION);
        WorkerStatusPublisher publisher = new WorkerStatusPublisher(state, () -> { }, () -> { });

        publisher.recordProcessed();
        publisher.recordProcessed();

        assertThat(state.peekProcessedCount()).isEqualTo(2);
    }

    @Test
    void workRouteHelpersReplaceDefinitionRoutesWhenDifferent() {
        WorkerState state = new WorkerState(DEFINITION);
        WorkerStatusPublisher publisher = new WorkerStatusPublisher(state, () -> { }, () -> { });

        publisher.workIn("dynamic.in");
        publisher.workOut("dynamic.out");

        assertThat(state.inboundRoutes()).containsExactlyInAnyOrder("dynamic.in");
        assertThat(state.outboundRoutes()).containsExactlyInAnyOrder("dynamic.out");
    }

    @Test
    void workRouteHelpersRetainDefinitionWhenUnchanged() {
        WorkerState state = new WorkerState(DEFINITION);
        WorkerStatusPublisher publisher = new WorkerStatusPublisher(state, () -> { }, () -> { });

        publisher.workIn("in.queue");
        publisher.workOut("out.queue");

        assertThat(state.inboundRoutes()).containsExactlyInAnyOrder("in.queue");
        assertThat(state.outboundRoutes()).containsExactlyInAnyOrder("out.queue");
    }

    @Test
    void emitHooksDelegateToRuntime() {
        AtomicBoolean full = new AtomicBoolean();
        AtomicBoolean delta = new AtomicBoolean();
        WorkerState state = new WorkerState(DEFINITION);
        WorkerStatusPublisher publisher = new WorkerStatusPublisher(state, () -> full.set(true), () -> delta.set(true));

        publisher.emitFull();
        publisher.emitDelta();

    assertThat(full.get()).isTrue();
    assertThat(delta.get()).isTrue();
    }
}
