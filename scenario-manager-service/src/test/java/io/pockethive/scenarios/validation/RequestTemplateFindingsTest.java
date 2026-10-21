package io.pockethive.scenarios.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.templatedata.JsonDocumentReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTemplateFindingsTest {
    private final RequestTemplateFindings projection = new RequestTemplateFindings();

    @Test
    void invalidPortableCallProducesFileLevelFindingsInsteadOfEscaping() throws Exception {
        try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
            assertThat(resource).isNotNull();
            String source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            var invalid = new JsonDocumentReader().readObject(source.replace("201", "201.0"));
            var findings = new ArrayList<ValidationFinding>();
            assertThat(projection.parse(invalid, "templates/account.json", findings)).isNull();
            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.path()).isEqualTo("templates/account.json");
                assertThat(finding.code()).isEqualTo("TEMPLATE_INVALID");
                assertThat(finding.message()).isEqualTo("Expected status must be an integer.");
            });
        }
    }

    @Test
    void preservesRequiredFieldPathsInBundleFindings() {
        var findings = new ArrayList<ValidationFinding>();
        assertThat(projection.parse(Map.of(), "call.yaml", findings)).isNull();
        assertThat(findings).extracting(ValidationFinding::path)
            .containsExactly("call.yaml:protocol", "call.yaml:serviceId", "call.yaml:callId");
        assertThat(findings).allMatch(finding -> finding.code().equals("TEMPLATE_REQUIRED_FIELD_MISSING"));
    }

    @Test
    void preservesInvalidApplyAsCategoryAndCanonicalAuthParsing() {
        var findings = new ArrayList<ValidationFinding>();
        var invalid = Map.of("authRef", Map.of("profileId", "token", "applyAs", "INVALID"));
        assertThat(projection.authReference(invalid, "call.yaml", findings)).isNull();
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("AUTH_REF_APPLY_AS_INVALID");
            assertThat(finding.path()).isEqualTo("call.yaml:authRef.applyAs");
        });
        findings.clear();
        var valid = Map.of("authRef", Map.of("profileId", "token", "applyAs", "http-header"));
        assertThat(projection.authReference(valid, "call.yaml", findings).profileId()).isEqualTo("token");
        assertThat(findings).isEmpty();
    }
}
