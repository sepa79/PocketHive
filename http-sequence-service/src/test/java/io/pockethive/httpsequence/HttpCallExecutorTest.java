package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpCallExecutorTest {
    @Test
    void unsupportedPortableExecutionCannotFallBackToNativeExecution() throws Exception {
        var nativeAttempts = new AtomicInteger();
        HttpCallExecutor executor = (target, call) -> {
            nativeAttempts.incrementAndGet();
            return new HttpCallResult(200, Map.of(), "native-response", null);
        };
        URI endpoint = URI.create("https://sut/service");
        var request = new RenderedCall("POST", "/service", "{}", Map.of());

        assertThatThrownBy(() -> executor.executeOnce(endpoint, request))
            .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("not supported");
        assertThat(nativeAttempts).hasValue(0);
        assertThat(executor.execute(endpoint, request).body()).isEqualTo("native-response");
        assertThat(nativeAttempts).hasValue(1);
    }
}
