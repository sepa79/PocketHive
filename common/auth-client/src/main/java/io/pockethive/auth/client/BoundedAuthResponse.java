package io.pockethive.auth.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Responsibility: Materialize at most one MiB and close/cancel the selected JDK response body before decoding.
 * Must not: Drain a rejected body, parse identity, expose upstream error bodies or follow redirects.
 * Contract: RESP-POCKET-AUTH-HTTP — docs/architecture/runtime-responsibilities.md#resp-pocket-auth-http.
 */
final class BoundedAuthResponse implements ClientHttpResponse {
    private static final int MAX_BYTES = 1_048_576;
    private final ClientHttpResponse response;
    private final InputStream body;

    BoundedAuthResponse(ClientHttpResponse response, long deadlineNanos) throws IOException {
        this.response = response;
        try (InputStream input = response.getBody()) {
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (true) {
                if (System.nanoTime() - deadlineNanos >= 0) throw new IOException("Auth response deadline exceeded");
                int received = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - bytes.size()));
                if (received < 0) break;
                bytes.write(buffer, 0, received);
                if (bytes.size() > MAX_BYTES) throw new IOException("Auth response exceeds limit");
            }
            body = new ByteArrayInputStream(bytes.toByteArray());
        }
    }

    @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
    @Override public String getStatusText() throws IOException { return response.getStatusText(); }
    @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
    @Override public InputStream getBody() { return body; }
    @Override public void close() {}
}
