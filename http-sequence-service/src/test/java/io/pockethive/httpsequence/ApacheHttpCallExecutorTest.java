package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApacheHttpCallExecutorTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n"})
    void portablePreservesTheExplicitBodyIncludingWhitespace(String body) throws Exception {
        HttpClient client = responseTransport(new byte[0], 0);
        var capture = org.mockito.ArgumentCaptor.forClass(ClassicHttpRequest.class);
        new ApacheHttpCallExecutor(client, client).executeOnce(endpoint, new RenderedCall("POST", "/service", body, request.headers()));
        verify(client).execute(capture.capture(), any(HttpClientResponseHandler.class));
        assertThat(capture.getValue().getEntity()).isNotNull();
        assertThat(org.apache.hc.core5.http.io.entity.EntityUtils.toByteArray(capture.getValue().getEntity()))
            .isEqualTo(body.getBytes(StandardCharsets.UTF_8));
    }
    private final URI endpoint = URI.create("https://sut/service");
    private final RenderedCall request = new RenderedCall(
        "POST", "/service", "{}", Map.of("Content-Type", "application/json"));

    @Test
    void portableResponseAtTheByteLimitIsNotTruncated() throws Exception {
        byte[] content = "é".repeat(524288).getBytes(StandardCharsets.UTF_8);
        HttpClient client = responseTransport(content, -1);
        var result = new ApacheHttpCallExecutor(client, client).executeOnce(endpoint, request);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.body().getBytes(StandardCharsets.UTF_8)).isEqualTo(content);
        assertThat(result.error()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void portableOversizeResponseFailsForKnownAndUnknownContentLengths(boolean lengthKnown) throws Exception {
        byte[] content = new byte[1024 * 1024 + 1];
        HttpClient client = responseTransport(content, lengthKnown ? content.length : -1);
        var result = new ApacheHttpCallExecutor(client, client).executeOnce(endpoint, request);
        assertThat(result.statusCode()).isEqualTo(-1);
        assertThat(result.body()).isEmpty();
        assertThat(result.error()).contains("exceeds one MiB");
    }

    @Test
    void portableTransportFailureDoesNotStartAnotherAttempt() throws Exception {
        var attempts = new AtomicInteger();
        HttpClient client = mock(HttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                attempts.incrementAndGet();
                throw new IOException("uncertain resource creation");
            });
        var result = new ApacheHttpCallExecutor(client, client).executeOnce(endpoint, request);
        assertThat(attempts).hasValue(1);
        assertThat(result.statusCode()).isEqualTo(-1);
        assertThat(result.error()).contains("uncertain resource creation");
    }

    @Test
    void malformedPortableUtf8FailsWithoutReplacementOrRetry() throws Exception {
        byte[] content = {(byte) 0xc3, (byte) 0x28};
        HttpClient client = responseTransport(content, -1);

        var result = new ApacheHttpCallExecutor(client, client).executeOnce(endpoint, request);

        assertThat(result.statusCode()).isEqualTo(-1);
        assertThat(result.body()).isEmpty();
        assertThat(result.error()).contains("MalformedInputException").doesNotContain("\uFFFD");
        verify(client, times(1)).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    private HttpClient responseTransport(byte[] content, long length) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                HttpClientResponseHandler<?> handler = invocation.getArgument(1);
                try (var response = new BasicClassicHttpResponse(200)) {
                    response.setEntity(new InputStreamEntity(new ByteArrayInputStream(content), length, ContentType.APPLICATION_JSON));
                    return handler.handleResponse(response);
                }
            });
        return client;
    }
}
