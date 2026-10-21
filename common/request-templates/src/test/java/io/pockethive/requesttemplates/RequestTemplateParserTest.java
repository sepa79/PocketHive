package io.pockethive.requesttemplates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.templatedata.JsonDocumentReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTemplateParserTest {
    private final RequestTemplateParser parser = new RequestTemplateParser();

    @Test
    void parsesDecodedHttpAndCanonicalAuthValues() {
        var document = http();
        document.put("authRef", Map.of("profileId", "token", "applyAs", "HTTP_HEADER"));
        var template = (HttpTemplateDefinition) parser.parse(document);
        assertThat(template.serviceId()).isEqualTo("svc");
        assertThat(template.callId()).isEqualTo("call");
        assertThat(template.pathTemplate()).isEqualTo("/{{ vars.id }}");
        assertThat(template.authRef().profileId()).isEqualTo("token");
    }

    @Test
    void reportsMissingFieldsAndUnsupportedProtocols() {
        try {
            parser.parse(Map.of());
            throw new AssertionError("Missing required fields were accepted");
        } catch (RequestTemplateException e) {
            assertThat(e.problems()).extracting(RequestTemplateProblem::field)
                .containsExactly("protocol", "serviceId", "callId");
        }
        var document = http();
        document.put("protocol", "SMTP");
        assertThatThrownBy(() -> parser.parse(document)).isInstanceOf(RequestTemplateException.class)
            .hasMessageContaining("Unsupported request-template protocol");
    }

    @Test
    void rejectsInlineAndMalformedAuthReferences() {
        var document = http();
        document.put("auth", Map.of());
        assertThatThrownBy(() -> parser.parse(document)).hasMessageContaining("inline auth");
        document.remove("auth");
        document.put("authRef", null);
        assertThatThrownBy(() -> parser.parse(document)).hasMessageContaining("authRef must be an object");
    }

    @Test
    void rejectsMissingHttpFieldsAndUnknownFields() {
        var document = http();
        document.remove("method");
        assertThatThrownBy(() -> parser.parse(document)).hasMessageContaining("'method'");
        document.put("method", "GET");
        document.put("unexpected", true);
        assertThatThrownBy(() -> parser.parse(document)).hasMessageContaining("unexpected");
    }

    @Test
    void portableAdmissionPreservesTheCanonicalParserFailureContract() throws Exception {
        try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
            assertThat(resource).isNotNull();
            String source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            var reader = new JsonDocumentReader();
            assertThat(parser.parse(reader.readObject(source))).isInstanceOf(PortableHttpCall.class);
            var invalid = reader.readObject(source.replace("201", "201.0"));
            assertThatThrownBy(() -> parser.parse(invalid)).isInstanceOfSatisfying(RequestTemplateException.class,
                failure -> assertThat(failure.problems()).containsExactly(new RequestTemplateProblem(
                    RequestTemplateProblemKind.INVALID_VALUE, "", "Expected status must be an integer.")));
        }
        assertThatThrownBy(() -> parser.parse(Map.of("profile", "unsupported")))
            .isInstanceOf(RequestTemplateException.class);
    }

    private Map<String, Object> http() {
        return new LinkedHashMap<>(Map.of("protocol", "HTTP", "serviceId", "svc", "callId", "call",
            "method", "GET", "pathTemplate", "/{{ vars.id }}", "bodyTemplate", ""));
    }
}
