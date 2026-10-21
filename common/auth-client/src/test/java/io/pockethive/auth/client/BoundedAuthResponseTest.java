package io.pockethive.auth.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;

class BoundedAuthResponseTest {
    @Test void tricklingBodyCannotKeepTheReaderAliveIndefinitely() {
        InputStream trickle = new InputStream() {
            @Override public int read() {
                try { Thread.sleep(20); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return ' ';
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                buffer[offset] = (byte) read();
                return 1;
            }
        };
        var response = new MockClientHttpResponse(trickle, HttpStatus.OK);
        long started = System.nanoTime();
        assertThatThrownBy(() -> new BoundedAuthResponse(response, started + Duration.ofMillis(75).toNanos()))
            .isInstanceOf(IOException.class).hasMessage("Auth response deadline exceeded");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }
}
