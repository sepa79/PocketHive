package io.pockethive.capabilities.api;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.requesttemplates.RequestTemplateProblem;
import io.pockethive.scenarios.ScenarioService;
import io.pockethive.scenarios.BundleTemplateSummary;
import io.pockethive.scenarios.BundleBeeSummary;
import io.pockethive.scenarios.ScenarioAccessDescriptor;
import io.pockethive.auth.contract.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioAuthoringServiceTest {
    private final ScenarioService scenarios = mock(ScenarioService.class);
    private final ScenarioAuthoringService authoring = new ScenarioAuthoringService(
        mock(CapabilityCatalogueService.class), scenarios, new ScenarioManagerAuthorization());

    @Test
    void advertisedHttpRequirementsMatchActualParserRejections() {
        var metadata = authoring.project(null).templatesContract();
        assertThat(metadata.get("httpRequiredFields"))
            .isEqualTo(List.of("protocol", "serviceId", "callId", "method", "pathTemplate"));
        Map<String, Object> template = Map.of("protocol", "HTTP", "serviceId", "service", "callId", "call",
            "method", "GET", "pathTemplate", "/test");
        RequestTemplateParser parser = new RequestTemplateParser();
        assertThat(parser.parse(template).serviceId()).isEqualTo("service");
        for (Object field : (List<?>) metadata.get("httpRequiredFields")) {
            var missing = new LinkedHashMap<>(template);
            missing.remove(field);
            var failure = catchThrowableOfType(RequestTemplateException.class, () -> parser.parse(missing));
            assertThat(failure.problems()).extracting(RequestTemplateProblem::field).contains(field.toString());
        }
    }

    @Test
    void exposesRequiredDescriptorFieldsIncludingProtocolVersion() {
        assertThat(authoring.project(null).scenario().get("requiredTopLevelFields"))
            .isEqualTo(List.of("protocolVersion", "id", "name", "template"));
    }

    @Test
    void retainsVariablesWireMetadataAndBundleLayout() {
        var view = authoring.project(null);
        assertThat(view.variables()).containsEntry("version", 1)
            .containsEntry("definitionScopes", List.of("GLOBAL", "SUT"))
            .containsEntry("definitionTypes", List.of("STRING", "INT", "FLOAT", "BOOL", "OBJECT"));
        assertThat(view.templatesContract()).containsEntry("root", "templates")
            .containsEntry("httpRoot", "templates/http");
        assertThat(view.sut()).containsEntry("root", "sut/<sutId>/sut.yaml");
        assertThat(view.auth()).containsEntry("referenceField", "authRef");
    }

    @Test
    void duplicateIdsUseEachBundlesFolderGrant() {
        var allowed = summary("team/a", "duplicate");
        var denied = summary("other/b", "duplicate");
        when(scenarios.listBundleTemplates()).thenReturn(List.of(denied, allowed));
        when(scenarios.findBundleAccess("team/a")).thenReturn(Optional.of(
            new ScenarioAccessDescriptor("duplicate", "team/a", "team")));
        when(scenarios.findBundleAccess("other/b")).thenReturn(Optional.of(
            new ScenarioAccessDescriptor("duplicate", "other/b", "other")));
        when(scenarios.findScenarioAccess("duplicate")).thenReturn(Optional.of(
            new ScenarioAccessDescriptor("duplicate", "other/b", "other")));
        assertThat(authoring.templates(user())).containsExactly(allowed);
        assertThat(authoring.project(user()).templateCatalog()).extracting(ScenarioTemplateView::bundleKey)
            .containsExactly("team/a");
    }

    @Test
    void missingBundleAccessDoesNotBorrowAccessFromScenarioId() {
        var summary = summary("missing", "duplicate");
        when(scenarios.findScenarioAccess("duplicate")).thenReturn(Optional.of(
            new ScenarioAccessDescriptor("duplicate", "team/a", "team")));
        assertThat(authoring.isRunnableTemplate(user(), summary)).isFalse();
    }

    @Test
    void malformedBundleRemainsVisibleThroughItsOwnAccess() {
        var summary = summary("team/broken", null);
        when(scenarios.findBundleAccess("team/broken")).thenReturn(Optional.of(
            new ScenarioAccessDescriptor(null, "team/broken", "team")));
        assertThat(authoring.isRunnableTemplate(user(), summary)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/templateCatalog/0/name", "/templateCatalog/0/description",
        "/templateCatalog/0/controllerImage", "/templateCatalog/0/bees/0/image",
        "/scenario/templateField", "/variables/file", "/auth/referenceField", "/endpoints/templates"})
    void fingerprintTracksPreviouslyOmittedResponseData(String pointer) {
        when(scenarios.listBundleTemplates()).thenReturn(List.of(summary("team/a", "demo")));
        var view = authoring.project(null);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode changed = mapper.valueToTree(view);
        int separator = pointer.lastIndexOf('/');
        ((ObjectNode) changed.at(pointer.substring(0, separator)))
            .put(pointer.substring(separator + 1), "changed");
        assertThat(authoring.fingerprint(mapper.convertValue(changed, AuthoringContractView.class)))
            .isNotEqualTo(view.fingerprint());
    }

    @Test
    void fingerprintIgnoresItsOwnValueAndMapInsertionOrder() {
        var view = authoring.project(null);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode changed = mapper.valueToTree(view);
        changed.put("fingerprint", "previous-digest");
        ObjectNode reversed = mapper.createObjectNode();
        var names = new java.util.ArrayList<String>();
        changed.get("scenario").fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        names.forEach(name -> reversed.set(name, changed.get("scenario").get(name)));
        changed.set("scenario", reversed);
        assertThat(authoring.fingerprint(mapper.convertValue(changed, AuthoringContractView.class)))
            .isEqualTo(view.fingerprint());
        assertThat(authoring.project(null).fingerprint()).isEqualTo(view.fingerprint());
    }

    private BundleTemplateSummary summary(String key, String id) {
        return new BundleTemplateSummary(key, key, key.split("/")[0], id, "Original", "Description",
            "controller:1", List.of(new BundleBeeSummary("processor", "processor:1")), id == null, null);
    }

    private AuthenticatedUserDto user() {
        return new AuthenticatedUserDto(UUID.randomUUID(), "tester", "Tester", true, AuthProvider.DEV,
            List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, PocketHivePermissionIds.RUN,
                PocketHiveResourceTypes.FOLDER, "team")));
    }
}
