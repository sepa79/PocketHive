package io.pockethive.acceptance.config;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TargetLoaderTest {
  @TempDir Path folder;
  private static final String TARGET = """
      ingress=http://localhost:8088/
      username=test-actor
      requestTimeout=PT1S
      operationTimeout=PT2S
      captureTimeout=PT1S
      pollInterval=PT0.01S
      templateId=explicit-fixture
      sutId=test-sut
      captureRole=processor
      captureDirection=OUT
      captureIoName=out
      sampleCount=2
      tapTtlSeconds=5
      expectedResponse={}
      evidenceDirectory=reports
      """;
  private Path file(String value) throws Exception { return Files.writeString(folder.resolve("target.properties"), value); }

  @Test void resolvesAnExplicitFileWithoutEnvironmentDefaults() throws Exception {
    var target = TargetLoader.load(file(TARGET));
    assertEquals("explicit-fixture", target.fixture().templateId());
    assertEquals(folder.resolve("reports"), target.api().evidenceDirectory());
  }
  @Test void missingValueFailsBeforeAnyTestRuns() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.load(file(TARGET.replace("username=test-actor\n", ""))));
  }
  @Test void unknownSettingFailsInsteadOfSilentlyUsingTheWrongConfiguration() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.load(file(TARGET + "requestTimout=PT2S\n")));
  }
  @Test void rejectsAnExpiredTapBudget() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.load(file(TARGET.replace("tapTtlSeconds=5", "tapTtlSeconds=1"))));
  }
  private static final String SCENARIO = """
      ingress=http://localhost:8088/
      username=test-actor
      requestTimeout=PT1S
      scenarioId=authoring-fixture
      evidenceDirectory=reports
      """;

  @Test void scenarioReadNeedsNoLifecycleOrCaptureSettings() throws Exception {
    var target = TargetLoader.loadScenario(file(SCENARIO));
    assertEquals("authoring-fixture", target.scenarioId());
    assertEquals(folder.resolve("reports"), target.api().evidenceDirectory());
  }
  @Test void targetKindsCannotBeSubstitutedOrMixed() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.load(file(SCENARIO)));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadScenario(file(TARGET)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadScenario(file(SCENARIO + "operationTimeout=PT2S\n")));
  }
  @Test void scenarioRequiresItsFixtureAndValidCommonSettings() throws Exception {
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadScenario(file(SCENARIO.replace("scenarioId=authoring-fixture\n", ""))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadScenario(file(SCENARIO.replace("requestTimeout=PT1S", "requestTimeout=PT0S"))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadScenario(file(SCENARIO.replace("http://localhost:8088/", "http://localhost:8088/backend/"))));
  }

  @Test void viewerRequiresExplicitObserverAndOperationSettingsButNoCapture() throws Exception {
    String config = SCENARIO + "cleanupUsername=admin\noperationTimeout=PT2S\npollInterval=PT0.01S\nsutId=test-sut\n";
    var target = TargetLoader.loadViewer(file(config));
    assertEquals("admin", target.cleanupUsername());
    assertEquals(java.time.Duration.ofSeconds(2), target.limits().operation());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadViewer(file(SCENARIO)));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadViewer(file(config + "captureTimeout=PT1S\n")));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadViewer(file(config.replace("cleanupUsername=admin", "cleanupUsername=test-actor"))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadViewer(file(config.replace("pollInterval=PT0.01S", "pollInterval=PT0S"))));
  }

  @Test void runnerRequiresDistinctFixturesAndExplicitScope() throws Exception {
    String config = SCENARIO + "cleanupUsername=admin\noperationTimeout=PT2S\npollInterval=PT0.01S\nsutId=test-sut\n"
        + "folder=allowed\ndeniedScenarioId=outside\n";
    var target = TargetLoader.loadRunner(file(config));
    assertEquals("allowed", target.folder());
    assertEquals("outside", target.deniedScenarioId());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadRunner(file(config.replace("folder=allowed\n", ""))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadRunner(file(config.replace("deniedScenarioId=outside", "deniedScenarioId=authoring-fixture"))));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadRunner(file(config + "captureTimeout=PT1S\n")));
  }

  @Test void networkAccessNeedsOnlyApiAndActorSettings() throws Exception {
    String config = SCENARIO.replace("scenarioId=authoring-fixture\n", "")
        + "runnerUsername=runner\nrunnerFolder=demo\n";
    var target = TargetLoader.loadNetworkAccess(file(config));
    assertEquals("runner", target.runnerUsername());
    assertEquals("demo", target.runnerFolder());
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadNetworkAccess(file(config + "scenarioId=unneeded\n")));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadNetworkAccess(file(config.replace("runnerFolder=demo\n", ""))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadNetworkAccess(file(config.replace("runnerUsername=runner", "runnerUsername=test-actor"))));
  }

  @Test void proxyRequiresItsProfileAndEndpointWithoutWeakeningLifecycleValidation() throws Exception {
    String config = TARGET + "networkProfileId=passthrough\nendpointId=default\n";
    var target = TargetLoader.loadProxy(file(config));
    assertEquals("passthrough", target.networkProfileId());
    assertEquals("default", target.endpointId());
    assertEquals("explicit-fixture", target.lifecycle().fixture().templateId());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadProxy(file(TARGET)));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.load(file(config)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadProxy(file(config.replace("endpointId=default", "endpointId="))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadProxy(file(config.replace("tapTtlSeconds=5", "tapTtlSeconds=1"))));
  }

  @Test void timeoutTargetRequiresExplicitMockSettingsAndCoversTheEntireTapLifetime() throws Exception {
    String config = TARGET + "mockUsername=admin\nmockPassword=test\nmappingId=slow\nquietWindow=PT2S\n";
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadTcpTimeout(file(config)));
    var target = TargetLoader.loadTcpTimeout(file(config.replace("tapTtlSeconds=5", "tapTtlSeconds=8")));
    assertEquals("slow", target.mappingId());
    assertEquals(java.time.Duration.ofSeconds(2), target.quietWindow());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadTcpTimeout(file(TARGET)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadTcpTimeout(file(config.replace("mockPassword=test\n", ""))));
  }

}
