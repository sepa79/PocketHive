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

    @Test
    void tcpResultEnvelopeCarriesRequestOutcomeAndMetrics() {
        TcpResultEnvelope result = TcpResultEnvelope.of(
            new TcpResultEnvelope.TcpRequestInfo("tcp", "tcps", "request_response", "tcps://sut:9000", "tcps://sut:9000"),
            new TcpResultEnvelope.TcpOutcome(
                TcpResultEnvelope.OUTCOME_TCP_RESPONSE,
                200,
                "PONG",
                null
            ),
            new TcpResultEnvelope.TcpMetrics(21, 3)
        );

        assertThat(result.kind()).isEqualTo(TcpResultEnvelope.KIND);
        assertThat(result.request().method()).isEqualTo("REQUEST_RESPONSE");
        assertThat(result.outcome().status()).isEqualTo(200);
        assertThat(result.metrics().durationMs()).isEqualTo(21L);
    }

    @Test
    void iso8583RequestEnvelopeNormalizesAndValidates() {
        Iso8583RequestEnvelope envelope = Iso8583RequestEnvelope.of(
            new Iso8583RequestEnvelope.Iso8583Request(
                "MC_2BYTE_LEN_BIN_BITMAP",
                " raw_hex ",
                "00AA11FF",
                Map.of("x-test", "true"),
                null
            )
        );

        assertThat(envelope.kind()).isEqualTo(Iso8583RequestEnvelope.KIND);
        assertThat(envelope.request().wireProfileId()).isEqualTo("MC_2BYTE_LEN_BIN_BITMAP");
        assertThat(envelope.request().payloadAdapter()).isEqualTo("RAW_HEX");
    }

    @Test
    void iso8583ResultEnvelopeCarriesRequestOutcomeAndMetrics() {
        Iso8583ResultEnvelope result = Iso8583ResultEnvelope.of(
            new Iso8583ResultEnvelope.Iso8583RequestInfo(
                "iso8583",
                "tcp",
                "send",
                "tcp://sut:6036",
                "MC_2BYTE_LEN_BIN_BITMAP",
                "raw_hex",
                48
            ),
            new Iso8583ResultEnvelope.Iso8583Outcome(
                Iso8583ResultEnvelope.OUTCOME_ISO8583_RESPONSE,
                200,
                "0210AABB",
                null
            ),
            new Iso8583ResultEnvelope.Iso8583Metrics(15, 0)
        );

        assertThat(result.kind()).isEqualTo(Iso8583ResultEnvelope.KIND);
        assertThat(result.request().method()).isEqualTo("SEND");
        assertThat(result.request().payloadAdapter()).isEqualTo("RAW_HEX");
        assertThat(result.outcome().responseHex()).isEqualTo("0210AABB");
        assertThat(result.metrics().durationMs()).isEqualTo(15L);
    }

}
