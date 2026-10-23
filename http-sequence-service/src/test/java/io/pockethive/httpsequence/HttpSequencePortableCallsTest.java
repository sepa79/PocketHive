package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.PortableHttpCallParser;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpSequencePortableCallsTest {
    private final HttpSequencePortableCalls calls = new HttpSequencePortableCalls();
    private final PortableHttpCall call = new PortableHttpCallParser().parse("""
        {"profile":"portable-v1","requires":["json_path"],
         "request":{"serviceId":"default","callId":"portable","protocol":"HTTP","method":"POST",
                    "pathTemplate":"{{ endpoint.path }}","bodyTemplate":"{{ record.id }}:{{ params.id }}",
                    "headersTemplate":{}},
         "response":{"kind":"JSON","soapAction":"","expectedStatus":200,"extract":{"value":"/value"},"namespaces":{}}}
        """);

    @Test
    void bindsRecordAndParamsSeparatelyAndReturnsResponseFieldsWithoutStateWrites() {
        Map<String, Object> record = Map.of("id", "0001", "unused", "keep");
        calls.validate(step(), call);
        var request = calls.render(call, URI.create("https://sut/service"), record, Map.of("id", "parameter"));
        assertThat(request.uri()).isEqualTo(URI.create("https://sut/service"));
        assertThat(request.body()).isEqualTo("0001:parameter");
        assertThat(calls.extract(call, new HttpCallResult(200, Map.of(), "{\"value\":\"  \"}", null)))
            .containsEntry("value", "  ");
        assertThat(record).containsOnlyKeys("id", "unused");
    }

    @Test
    void portableEngineDoesNotExposeNativeAliases() {
        var nativeAlias = new PortableHttpCallParser().parse("""
            {"profile":"portable-v1","requires":["json_path"],
             "request":{"serviceId":"default","callId":"portable","protocol":"HTTP","method":"POST",
                        "pathTemplate":"{{ endpoint.path }}","bodyTemplate":"{{ payload.id }}","headersTemplate":{}},
             "response":{"kind":"JSON","soapAction":"","expectedStatus":200,"extract":{"value":"/value"},"namespaces":{}}}
            """);
        assertThatThrownBy(() -> calls.render(nativeAlias, URI.create("https://sut/"), Map.of("id", "0001"), Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportErrorsAreReportedBeforeInterpretingAResponseDocument() {
        String diagnostic = "java.net.SocketTimeoutException: response timed out";
        for (var result : List.of(new HttpCallResult(-1, Map.of(), "", diagnostic),
                new HttpCallResult(200, Map.of(), "sensitive body", diagnostic))) {
            assertThatThrownBy(() -> calls.extract(call, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Portable HTTP transport failed").hasMessageContaining(diagnostic)
                .hasMessageNotContaining("sensitive body").hasMessageNotContaining("JSON").hasMessageNotContaining("XML");
        }
        assertThatThrownBy(() -> calls.extract(call, new HttpCallResult(-1, Map.of(), "", null)))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("transport failed (status -1)");
    }

    private HttpSequenceWorkerConfig.Step step() {
        return new HttpSequenceWorkerConfig.Step("portable", "portable", null, false, null, List.of(), List.of());
    }
}
