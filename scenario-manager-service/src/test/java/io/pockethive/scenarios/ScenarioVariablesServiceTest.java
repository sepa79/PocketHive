package io.pockethive.scenarios;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioVariablesServiceTest extends ScenarioComponentTestFixture {
    private ScenarioVariablesService variables;

    @BeforeEach
    void setUpVariables() {
        variables = new ScenarioVariablesService(scenarios, validator);
    }

    @Test
    void validatesCoverageAndResolvesObjectValuesForCanonicalSut() throws Exception {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "sut-A");
        scenarios.reload();
        String raw = """
            version: 1
            definitions:
              - name: customers
                scope: sut
                type: object
                required: true
            profiles:
              - id: default
                name: Default
            values:
              sut:
                default:
                  sut-A:
                    customers:
                      first:
                        currency: GBP
            """;

        VariablesValidationResult validation = variables.write("scenario-1", raw);
        VariablesResolutionResult resolved = variables.resolve("scenario-1", "default", "sut-A");

        assertThat(validation.warnings()).isEmpty();
        assertThat(resolved.vars().get("customers")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) resolved.vars().get("customers")).containsKey("first")).isTrue();
    }

    @Test
    void rejectsValuesForUnknownCanonicalSut() throws Exception {
        writeBundleScenario("scenario-1");
        writeBundleSut("scenario-1", "sut-A");
        scenarios.reload();
        VariablesDocument document = variables.parse("""
            version: 1
            definitions:
              - name: customerId
                scope: sut
                type: string
            profiles:
              - id: default
                name: Default
            values:
              sut:
                default:
                  ghost:
                    customerId: "123"
            """);

        assertThatThrownBy(() -> variables.validate("scenario-1", document))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("references unknown sutId 'ghost'");
    }
}
