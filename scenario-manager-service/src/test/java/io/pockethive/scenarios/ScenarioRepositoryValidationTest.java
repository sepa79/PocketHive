package io.pockethive.scenarios;

import io.pockethive.capabilities.CapabilityCatalogueService;
import io.pockethive.scenarios.validation.BundleValidationInput;
import io.pockethive.scenarios.validation.BundleValidationResult;
import io.pockethive.scenarios.validation.BundleValidationSource;
import io.pockethive.scenarios.validation.ScenarioBundleValidator;
import io.pockethive.scenarios.validation.ValidationFinding;
import io.pockethive.scenarios.validation.ValidationSeverity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRepositoryValidationTest {

  @Test
  void repositoryScenarioBundlesPassScenarioManagerValidator() throws Exception {
    Path repoRoot = Path.of(System.getProperty("user.dir")).getParent();
    Path scenariosRoot = repoRoot.resolve("scenarios");
    Path capabilitiesRoot = repoRoot.resolve("scenario-manager-service").resolve("capabilities");
    assertThat(Files.isDirectory(scenariosRoot)).as("repo scenarios directory").isTrue();
    assertThat(Files.isDirectory(capabilitiesRoot)).as("repo capabilities directory").isTrue();

    CapabilityCatalogueService capabilities = new CapabilityCatalogueService(capabilitiesRoot);
    capabilities.reload();
    ScenarioBundleValidator validator = new ScenarioBundleValidator(capabilities, "latest", "test");

    List<Path> scenarioFiles;
    try (Stream<Path> stream = Files.walk(scenariosRoot)) {
      scenarioFiles = stream
          .filter(path -> path.getFileName().toString().equals(ScenarioBundleLayout.SCENARIO_DESCRIPTOR_FILE))
          .sorted(Comparator.comparing(path -> scenariosRoot.relativize(path).toString()))
          .toList();
    }
    assertThat(scenarioFiles).isNotEmpty();

    List<String> failures = scenarioFiles.stream()
        .map(path -> validateBundle(validator, scenariosRoot, path))
        .flatMap(List::stream)
        .toList();

    assertThat(failures).isEmpty();
  }

  private static List<String> validateBundle(
      ScenarioBundleValidator validator,
      Path scenariosRoot,
      Path scenarioFile
  ) {
    Path bundleRoot = scenarioFile.getParent();
    String bundleKey = scenariosRoot.relativize(bundleRoot).toString().replace('\\', '/');
    try {
      BundleValidationResult result = validator.validate(new BundleValidationInput(
          BundleValidationSource.SCENARIO_MANAGER,
          bundleRoot,
          bundleKey,
          bundleKey,
          null,
          List.of(),
          null));
      return result.findings().stream()
          .filter(finding -> finding.severity() == ValidationSeverity.ERROR)
          .map(finding -> formatFinding(bundleKey, finding))
          .toList();
    } catch (Exception e) {
      return List.of(bundleKey + ": " + e.getMessage());
    }
  }

  private static String formatFinding(String bundleKey, ValidationFinding finding) {
    return "%s: %s %s: %s".formatted(
        bundleKey,
        finding.code(),
        finding.path(),
        finding.message());
  }
}
