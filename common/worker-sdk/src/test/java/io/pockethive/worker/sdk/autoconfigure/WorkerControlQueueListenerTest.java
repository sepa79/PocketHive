package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

@ExtendWith(MockitoExtension.class)
class WorkerControlQueueListenerTest {

    @Mock
    private WorkerControlPlaneRuntime runtime;

    @Test
    void rejectsAndDoesNotRequeueBlankPayload() {
        WorkerControlQueueListener listener = new WorkerControlQueueListener(runtime);

        assertThatThrownBy(() -> listener.onControl(" ", "signal.status-request.sw1.generator.inst1", null))
            .isInstanceOf(AmqpRejectAndDontRequeueException.class)
            .hasMessageContaining("Control payload must not be null or blank");
    }

    @Test
    void rejectsAndDoesNotRequeueWhenRuntimeFailsToParseMessage() {
        WorkerControlQueueListener listener = new WorkerControlQueueListener(runtime);
        when(runtime.handle("{}", "signal.status-request.sw1.generator.inst1"))
            .thenThrow(new RuntimeException(new TestJsonProcessingException("bad json")));

        assertThatThrownBy(() -> listener.onControl("{}", "signal.status-request.sw1.generator.inst1", null))
            .isInstanceOf(AmqpRejectAndDontRequeueException.class)
            .hasMessageContaining("Invalid control-plane message");
    }

    private static final class TestJsonProcessingException extends JsonProcessingException {
        private TestJsonProcessingException(String msg) {
            super(msg);
        }
    }
}

