package io.pockethive.scenarios;

import java.nio.file.Path;

/**
 * Responsibility: resolve canonical bundle paths and the default worker mount destination.
 * Must not: read files, validate containment or choose runtime configuration overrides.
 * Contract: RESP-SCENARIO-BUNDLE-LAYOUT — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-layout.
 */
public final class ScenarioBundleLayout {
    public static final String CONTAINER_ROOT = "/app/scenario";
    public static final String SUT_ROOT = "sut";
    public static final String SCHEMAS_ROOT = "schemas";
    public static final String SCENARIO_DESCRIPTOR_FILE = "scenario.yaml";
    public static final String SUT_DESCRIPTOR_FILE = "sut.yaml";
    public static final String SUT_DESCRIPTOR_PATTERN = SUT_ROOT + "/<sutId>/" + SUT_DESCRIPTOR_FILE;
    public static final String VARIABLES_FILE = "variables.yaml";
    public static final String AUTH_PROFILES_FILE = "authProfiles.yaml";
    public static final String TEMPLATES_ROOT = "templates";
    public static final String HTTP_TEMPLATES_ROOT = TEMPLATES_ROOT + "/http";

    private ScenarioBundleLayout() {
    }

    public static Path sutRoot(Path bundleDir) {
        return bundleDir.resolve(SUT_ROOT).normalize();
    }

    public static Path sutDirectory(Path bundleDir, String sutId) {
        return bundleDir.resolve(SUT_ROOT).resolve(sutId).normalize();
    }

    public static Path templatesRoot(Path bundleDir) {
        return bundleDir.resolve(TEMPLATES_ROOT).normalize();
    }

    public static Path schemasRoot(Path bundleDir) {
        return bundleDir.resolve(SCHEMAS_ROOT).normalize();
    }

    public static Path scenarioDescriptorFile(Path bundleDir) {
        return bundleDir.resolve(SCENARIO_DESCRIPTOR_FILE).normalize();
    }

    public static Path sutDescriptorFile(Path sutDir) {
        return sutDir.resolve(SUT_DESCRIPTOR_FILE).normalize();
    }

    public static Path variablesFile(Path bundleDir) {
        return bundleDir.resolve(VARIABLES_FILE).normalize();
    }

    public static Path authProfilesFile(Path bundleDir) {
        return bundleDir.resolve(AUTH_PROFILES_FILE).normalize();
    }

    public static boolean isScenarioDescriptor(Path path) {
        Path fileName = path != null ? path.getFileName() : null;
        return fileName != null && SCENARIO_DESCRIPTOR_FILE.equals(fileName.toString());
    }
}
