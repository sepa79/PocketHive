package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkerStatusSchedulerTest {

    @Test
    void emitStatusDeltaDelegatesToRuntime() {
        WorkerControlPlaneRuntime runtime = mock(WorkerControlPlaneRuntime.class);
        WorkerStatusSchedulerProperties properties = new WorkerStatusSchedulerProperties();
        WorkerStatusScheduler scheduler = new WorkerStatusScheduler(runtime, properties);

        scheduler.emitStatusDelta();

        verify(runtime).emitStatusDelta();
    }

    @Test
    void defaultIntervalMatchesExistingCadence() {
        WorkerStatusSchedulerProperties properties = new WorkerStatusSchedulerProperties();

        assertThat(properties.getDeltaInterval()).isEqualTo(Duration.ofSeconds(5));
    }
}
