package io.pockethive.requesttemplates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortableHttpCallParserTest {
    private final PortableHttpCallParser parser = new PortableHttpCallParser();

    @ParameterizedTest
    @ValueSource(strings = {"\uD800", "\uDC00"})
    void rejectsUnpairedSurrogatesAtBothEntryPoints(String malformed) throws Exception {
        String source = example().replace("{{ endpoint.path }}", malformed);
        assertThatThrownBy(() -> parser.parse(source)).isInstanceOf(TemplateDataException.class);
        var document = new JsonDocumentReader().readObject(example());
        request(document).put("pathTemplate", malformed);
        assertThatThrownBy(() -> parser.parse(document)).isInstanceOfSatisfying(TemplateDataException.class,
            failure -> assertThat(failure.code()).isEqualTo(TemplateDataError.INVALID_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"body", "header", "response", "key"})
    void validatesNestedStringValuesAndKeys(String location) throws Exception {
        var document = new JsonDocumentReader().readObject(example());
        String malformed = String.valueOf((char) 0xD800);
        switch (location) {
            case "body" -> request(document).put("bodyTemplate", malformed);
            case "header" -> request(document).put("headersTemplate", Map.of("X-Test", malformed));
            case "response" -> response(document).put("extract", Map.of("accountNumber", "/" + malformed));
            case "key" -> request(document).put("headersTemplate", Map.of(malformed, "value"));
            default -> throw new AssertionError(location);
        }
        assertThatThrownBy(() -> parser.parse(document)).isInstanceOfSatisfying(TemplateDataException.class,
            failure -> assertThat(failure.code()).isEqualTo(TemplateDataError.INVALID_VALUE));
    }

    @Test
    void appliesTheCanonicalDocumentBudgetToDecodedInput() throws Exception {
        var document = new JsonDocumentReader().readObject(example());
        var headers = new LinkedHashMap<String, String>();
        for (int index = 0; index < 17; index++) {
            headers.put("X-Header-" + index, "a".repeat(PortableHttpCallParser.MAX_TEMPLATE_LENGTH));
        }
        request(document).put("headersTemplate", headers);
        assertThatThrownBy(() -> parser.parse(document)).isInstanceOfSatisfying(TemplateDataException.class,
            failure -> assertThat(failure.code()).isEqualTo(TemplateDataError.LIMIT_EXCEEDED));
    }

    @Test
    void preservesValidUnicodeAndIntegerStatusAcrossEntryPoints() throws Exception {
        String source = example().replace("{{ endpoint.path }}", "/zażółć/😀");
        var direct = parser.parse(source);
        var decoded = parser.parse(new JsonDocumentReader().readObject(source));
        assertThat(decoded).isEqualTo(direct);
        assertThat(decoded.request().pathTemplate()).isEqualTo("/zażółć/😀");
        assertThat(decoded.response().expectedStatus()).isEqualTo(201);
    }

    @Test
    void doesNotCoerceDecodedNumbersOrNestedValues() throws Exception {
        for (Object status : new Object[] {(byte) 201, (short) 201, 201L, 201.0, new BigDecimal("201.0")}) {
            var document = new JsonDocumentReader().readObject(example());
            response(document).put("expectedStatus", status);
            assertThatThrownBy(() -> parser.parse(document)).isInstanceOf(TemplateDataException.class)
                .hasMessageContaining("Expected status must be an integer");
        }
        var document = new JsonDocumentReader().readObject(example());
        request(document).put("headersTemplate", Map.of("X-Test", Map.of("nested", "value")));
        assertThatThrownBy(() -> parser.parse(document)).isInstanceOf(TemplateDataException.class)
            .hasMessageContaining("Invalid portable text value");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(Map<String, Object> document) {
        return (Map<String, Object>) document.get("request");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> response(Map<String, Object> document) {
        return (Map<String, Object>) document.get("response");
    }

    private String example() throws Exception {
        try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
            assertThat(resource).isNotNull();
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
