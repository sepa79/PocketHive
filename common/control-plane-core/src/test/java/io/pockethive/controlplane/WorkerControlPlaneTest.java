package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.worker.WorkerConfigCommand;
import io.pockethive.controlplane.worker.WorkerControlPlane;
import io.pockethive.controlplane.worker.WorkerSignalListener;
import io.pockethive.controlplane.worker.WorkerStatusRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerControlPlaneTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkerControlPlane plane;

    @BeforeEach
    void setUp() {
        plane = WorkerControlPlane.builder(mapper).build();
    }

    @Test
    void dispatchesConfigUpdatesWithParsedPayload() throws Exception {
        ControlSignal signal = ControlSignal.forInstance("config-update", "sw1", "generator", "inst", null, null,
            CommandTarget.INSTANCE, Map.of("data", Map.of("enabled", true, "ratePerSec", 5)));
        AtomicReference<WorkerConfigCommand> ref = new AtomicReference<>();

        WorkerSignalListener listener = new WorkerSignalListener() {
            @Override
            public void onConfigUpdate(WorkerConfigCommand command) {
                ref.set(command);
            }
        };

        plane.consume(mapper.writeValueAsString(signal), "sig.config-update", listener);

        WorkerConfigCommand command = ref.get();
        assertThat(command).isNotNull();
        assertThat(command.signal()).isEqualTo(signal);
        assertThat(command.envelope().routingKey()).isEqualTo("sig.config-update");
        assertThat(command.data()).containsEntry("ratePerSec", 5);
        assertThat(command.enabled()).isTrue();
    }

    @Test
    void parsesStringEnabledFlag() throws Exception {
        ControlSignal signal = ControlSignal.forInstance("config-update", "sw1", "generator", "inst", null, null,
            CommandTarget.INSTANCE, Map.of("data", Map.of("enabled", "false")));
        AtomicReference<WorkerConfigCommand> ref = new AtomicReference<>();

        plane.consume(mapper.writeValueAsString(signal), "sig.config-update", new WorkerSignalListener() {
            @Override
            public void onConfigUpdate(WorkerConfigCommand command) {
                ref.set(command);
            }
        });

        assertThat(ref.get()).isNotNull();
        assertThat(ref.get().enabled()).isFalse();
    }

    @Test
    void dispatchesStatusRequest() throws Exception {
        ControlSignal signal = ControlSignal.forInstance("status-request", "sw1", "generator", "inst", null, null);
        AtomicReference<WorkerStatusRequest> ref = new AtomicReference<>();

        WorkerSignalListener listener = new WorkerSignalListener() {
            @Override
            public void onStatusRequest(WorkerStatusRequest request) {
                ref.set(request);
            }
        };

        plane.consume(mapper.writeValueAsString(signal), "sig.status-request", listener);

        WorkerStatusRequest request = ref.get();
        assertThat(request).isNotNull();
        assertThat(request.signal()).isEqualTo(signal);
    }

    @Test
    void forwardsUnsupportedSignals() throws Exception {
        ControlSignal signal = new ControlSignal("unknown", null, null, null, null, null,
            CommandTarget.ALL, Map.of());
        AtomicReference<WorkerSignalListener.WorkerSignalContext> ref = new AtomicReference<>();

        WorkerSignalListener listener = new WorkerSignalListener() {
            @Override
            public void onUnsupported(WorkerSignalContext context) {
                ref.set(context);
            }
        };

        plane.consume(mapper.writeValueAsString(signal), "sig.unknown", listener);

        WorkerSignalListener.WorkerSignalContext context = ref.get();
        assertThat(context).isNotNull();
        assertThat(context.envelope().signal()).isEqualTo(signal);
        assertThat(context.payload()).contains("\"unknown\"");
    }
}

