package io.pockethive.scenarios;

import java.nio.file.Path;

public final class ScenarioBundleLayout {
    public static final String SCENARIO_DESCRIPTOR_FILE = "scenario.yaml";
    public static final String SUT_DESCRIPTOR_FILE = "sut.yaml";
    public static final String SUT_DESCRIPTOR_PATTERN = "sut/<sutId>/" + SUT_DESCRIPTOR_FILE;
    public static final String VARIABLES_FILE = "variables.yaml";
    public static final String AUTH_PROFILES_FILE = "authProfiles.yaml";
    public static final String TEMPLATES_ROOT = "templates";
    public static final String HTTP_TEMPLATES_ROOT = TEMPLATES_ROOT + "/http";

    private ScenarioBundleLayout() {
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
