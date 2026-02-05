package io.pockethive.worker.sdk.autoconfigure;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkerControlQueueListenerTest {

    @Mock
    private WorkerControlPlaneRuntime runtime;

    @Test
    void dropsBlankPayloadWithoutThrowing() {
        WorkerControlQueueListener listener = new WorkerControlQueueListener(runtime);

        assertThatCode(() -> listener.onControl(" ", "signal.status-request.sw1.generator.inst1", null))
            .doesNotThrowAnyException();
        verifyNoInteractions(runtime);
    }

    @Test
    void dropsPoisonPayloadWithoutThrowing() {
        WorkerControlQueueListener listener = new WorkerControlQueueListener(runtime);
        when(runtime.handle("{}", "signal.status-request.sw1.generator.inst1"))
            .thenThrow(new RuntimeException(new TestJsonProcessingException("bad json")));

        assertThatCode(() -> listener.onControl("{}", "signal.status-request.sw1.generator.inst1", null))
            .doesNotThrowAnyException();
        verify(runtime).handle("{}", "signal.status-request.sw1.generator.inst1");
    }

    private static final class TestJsonProcessingException extends JsonProcessingException {
        private TestJsonProcessingException(String msg) {
            super(msg);
        }
    }
}
