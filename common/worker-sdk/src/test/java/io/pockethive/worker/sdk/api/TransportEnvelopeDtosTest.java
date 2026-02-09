package io.pockethive.worker.sdk.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransportEnvelopeDtosTest {

    @Test
    void httpRequestEnvelopeRequiresCanonicalKind() {
        HttpRequestEnvelope.HttpRequest request = new HttpRequestEnvelope.HttpRequest(
            "post",
            "/test",
            Map.of("content-type", "application/json"),
            Map.of("value", 42)
        );

        HttpRequestEnvelope envelope = HttpRequestEnvelope.of(request);

        assertThat(envelope.kind()).isEqualTo(HttpRequestEnvelope.KIND);
        assertThat(envelope.request().method()).isEqualTo("POST");
    }

    @Test
    void httpRequestEnvelopeRejectsUnexpectedKind() {
        HttpRequestEnvelope.HttpRequest request = new HttpRequestEnvelope.HttpRequest(
            "POST",
            "/test",
            Map.of(),
            "{}"
        );

        assertThatThrownBy(() -> new HttpRequestEnvelope("HTTP", request))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported kind");
    }

    @Test
    void httpResultEnvelopeCarriesRequestOutcomeAndMetrics() {
        HttpResultEnvelope result = HttpResultEnvelope.of(
            new HttpResultEnvelope.HttpRequestInfo("http", "https", "post", "https://sut", "/api", "https://sut/api"),
            new HttpResultEnvelope.HttpOutcome(
                HttpResultEnvelope.OUTCOME_HTTP_RESPONSE,
                200,
                Map.of("content-type", List.of("application/json")),
                "{\"ok\":true}",
                null
            ),
            new HttpResultEnvelope.HttpMetrics(15, 2)
        );

        assertThat(result.kind()).isEqualTo(HttpResultEnvelope.KIND);
        assertThat(result.request().method()).isEqualTo("POST");
        assertThat(result.outcome().status()).isEqualTo(200);
        assertThat(result.metrics().durationMs()).isEqualTo(15L);
    }

    @Test
    void tcpRequestEnvelopeNormalizesAndValidates() {
        TcpRequestEnvelope envelope = TcpRequestEnvelope.of(
            new TcpRequestEnvelope.TcpRequest(" REQUEST_RESPONSE ", " payload ", Map.of(), " ETX ", 1024)
        );

        assertThat(envelope.kind()).isEqualTo(TcpRequestEnvelope.KIND);
        assertThat(envelope.request().behavior()).isEqualTo("REQUEST_RESPONSE");
        assertThat(envelope.request().body()).isEqualTo(" payload ");
        assertThat(envelope.request().endTag()).isEqualTo("ETX");
    }

    @Test
    void tcpRequestEnvelopeAllowsEmptyBody() {
        TcpRequestEnvelope envelope = TcpRequestEnvelope.of(
            new TcpRequestEnvelope.TcpRequest("REQUEST_RESPONSE", "", Map.of(), null, 1024)
        );

        assertThat(envelope.request().body()).isEmpty();
    }
}
