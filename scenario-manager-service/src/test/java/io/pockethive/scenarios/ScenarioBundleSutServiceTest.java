package io.pockethive.scenarios;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioBundleSutServiceTest extends ScenarioComponentTestFixture {
    private ScenarioBundleSutService bundleSuts;

    @BeforeEach
    void setUpBundleSuts() {
        bundleSuts = new ScenarioBundleSutService(scenarios, validator);
    }

    @Test
    void writesReadsListsAndDeletesCanonicalBundleSut() throws Exception {
        writeBundleScenario("scenario-1");
        scenarios.reload();
        String raw = """
            id: sut-A
            name: SUT A
            endpoints:
              api:
                kind: http
                baseUrl: http://example.local
            """;

        bundleSuts.writeRaw("scenario-1", "sut-A", raw);

        assertThat(bundleSuts.readRaw("scenario-1", "sut-A")).isEqualTo(raw);
        assertThat(bundleSuts.read("scenario-1", "sut-A").id()).isEqualTo("sut-A");
        assertThat(bundleSuts.list("scenario-1")).containsExactly("sut-A");
        bundleSuts.delete("scenario-1", "sut-A");
        assertThat(bundleSuts.readRaw("scenario-1", "sut-A")).isNull();
    }

    @Test
    void refusesNonCanonicalSutDescriptorName() throws Exception {
        writeBundleScenario("scenario-1");
        Path sut = Files.createDirectories(scenariosDir.resolve("scenario-1/sut/sut-yml"));
        Files.writeString(sut.resolve("sut.yml"), "id: sut-yml\nname: SUT YML\n");
        scenarios.reload();

        assertThatThrownBy(() -> bundleSuts.read("scenario-1", "sut-yml"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no sut.yaml");
    }
}
