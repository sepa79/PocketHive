package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioConfigurationPreparationTest {
    @Test void preservesValuesTypesNestedPathsEmbeddedStringsAndSingleExpansion() {
        Map<String, Object> vars = Map.of("threads", 4, "enabled", true,
            "object", Map.of("name", "A"), "literal", "{{ vars.threads }}");
        var result = ScenarioConfigurationPreparation.applyVariables(Map.of(
            "threadCount", "{{ vars.threads }}", "enabled", "{{ vars.enabled }}",
            "message", Map.of("body", "item-{{ vars.object.name }}"),
            "nested", List.of("{{ vars.object }}", "{{ vars.literal }}"), "vars", Map.of("old", 1)), vars);
        assertEquals(4, result.get("threadCount"));
        assertEquals(true, result.get("enabled"));
        assertEquals(Map.of("body", "item-A"), result.get("message"));
        assertEquals(List.of(Map.of("name", "A"), "{{ vars.threads }}"), result.get("nested"));
        assertEquals(vars, result.get("vars"));
    }

    @Test void retainsTheEmptyMapGateAndMissingNullFailures() {
        Map<String, Object> config = Map.of("threadCount", "{{ vars.missing }}");
        assertSame(config, ScenarioConfigurationPreparation.applyVariables(config, Map.of()));
        assertThrows(IllegalStateException.class, () -> ScenarioConfigurationPreparation.applyVariables(config, Map.of("other", 1)));
        Map<String, Object> vars = new HashMap<>();
        vars.put("missing", null);
        assertThrows(IllegalStateException.class, () -> ScenarioConfigurationPreparation.applyVariables(config, vars));
    }

    @Test void sutInterpolationKeepsTheTopLevelScopeAndSuffix() {
        var sut = new SutEnvironment("sut", "SUT", null,
            Map.of("api", new SutEndpoint("HTTP", "https://sut.test", null)));
        String source = "{{ sut.endpoints['api'].baseUrl }}/suffix";
        var result = ScenarioConfigurationPreparation.applySut(Map.of("baseUrl", source,
            "nested", Map.of("baseUrl", source)), sut);
        assertEquals("https://sut.test/suffix", result.get("baseUrl"));
        assertEquals(Map.of("baseUrl", source), result.get("nested"));
        assertThrows(IllegalStateException.class, () -> ScenarioConfigurationPreparation.applySut(
            Map.of("baseUrl", "{{ sut.endpoints['missing'].baseUrl }}"), sut));
    }

    @Test void dependenciesUseTheBootstrapMatcherAndSkipVariableValues() {
        assertEquals(java.util.Set.of("count", "customer"), ScenarioConfigurationPreparation.variableNames(Map.of("threads", "{{ vars.count }}",
            "message", "value-{{ vars.customer.name }}", "literal", "vars.literal",
            "vars", Map.of("business", "{{ vars.opaque }}"))));
    }
}
