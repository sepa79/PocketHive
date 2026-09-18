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

  @Test void outcomeObservationRequiresAllExplicitSettings() throws Exception {
    String additions = "grafanaUsername=user\ngrafanaPassword=pass\ngrafanaDatasourceUid=chosen\noutcomeTable=events\n";
    var target = TargetLoader.loadTxOutcome(file(TARGET + additions));
    assertEquals("chosen", target.datasourceUid());
    assertEquals("events", target.outcomeTable());
    assertEquals("explicit-fixture", target.lifecycle().fixture().templateId());
    for (String line : additions.split("\n")) {
      assertThrows(IllegalArgumentException.class,
          () -> TargetLoader.loadTxOutcome(file(TARGET + additions.replace(line + "\n", ""))));
    }
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadTxOutcome(file(TARGET + additions.replace("grafanaPassword=pass", "grafanaPassword="))));
  }

  @Test void exportRequiresAnExplicitExistingLocalRuntimeRoot() throws Exception {
    String settings = TARGET + "redisConnectionId=R:redis:6379:0\nruntimeRoot=" + folder + "\n";
    assertEquals(folder.toRealPath(), TargetLoader.loadExport(file(settings)).runtimeRoot());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadExport(file(TARGET)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadExport(file(settings.replace(folder.toString(), "relative"))));
    assertThrows(java.io.IOException.class,
        () -> TargetLoader.loadExport(file(settings.replace(folder.toString(), folder.resolve("missing").toString()))));
  }

  @Test void redisDataRequiresLifecycleAndExplicitConnection() throws Exception {
    var target = TargetLoader.loadRedisData(file(TARGET + "redisConnectionId=R:redis:6379:0\n"));
    assertEquals("R:redis:6379:0", target.connectionId());
    assertEquals("explicit-fixture", target.lifecycle().fixture().templateId());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadRedisData(file(TARGET)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadRedisData(file(TARGET + "redisConnectionId=\n")));
  }

  @Test void redisFixtureRequiresExplicitConnectionAndNoLifecycleSettings() throws Exception {
    String config = SCENARIO.replace("scenarioId=authoring-fixture\n", "") + "redisConnectionId=R:redis:6379:0\n";
    assertEquals("R:redis:6379:0", TargetLoader.loadRedisFixture(file(config)).connectionId());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadApi(file(config)));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadRedisFixture(file(SCENARIO)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadRedisFixture(file(config.replace("redisConnectionId=R:redis:6379:0", "redisConnectionId="))));
  }

  @Test void apiTargetRequiresOnlyCommonSettingsAndRejectsFixtureConfiguration() throws Exception {
    String config = SCENARIO.replace("scenarioId=authoring-fixture\n", "");
    var target = TargetLoader.loadApi(file(config));
    assertEquals(folder.resolve("reports"), target.evidenceDirectory());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadApi(file(SCENARIO)));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadApi(file(TARGET)));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadApi(file(config.replace("username=test-actor\n", ""))));
    assertThrows(IllegalArgumentException.class,
        () -> TargetLoader.loadApi(file(config.replace("requestTimeout=PT1S", "requestTimeout=PT0S"))));
  }

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

  @Test void provisionedAuthRequiresDistinctExplicitScenarioScopes() throws Exception {
    String config = """
        ingress=http://localhost:8088/
        username=admin
        requestTimeout=PT1S
        evidenceDirectory=reports
        folder=fixtures
        bundle=fixtures/one
        scenarioId=one
        siblingScenarioId=two
        outsideScenarioId=three
        sutId=sut
        operationTimeout=PT5S
        pollInterval=PT0.1S
        """;
    var target = TargetLoader.loadProvisionedAuth(file(config));
    assertEquals("fixtures/one", target.bundle());
    assertEquals("two", target.siblingScenarioId());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadProvisionedAuth(file(config.replace("bundle=fixtures/one\n", ""))));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadProvisionedAuth(file(config.replace("siblingScenarioId=two", "siblingScenarioId=one"))));
  }
  @Test void managementTargetRequiresAnExplicitTapAndEnoughTimeToCloseIt() throws Exception {
    String config = """
        ingress=http://localhost:8088/
        username=admin
        requestTimeout=PT1S
        evidenceDirectory=reports
        folder=fixtures
        bundle=fixtures/one
        scenarioId=one
        siblingScenarioId=two
        outsideScenarioId=three
        sutId=sut
        operationTimeout=PT5S
        pollInterval=PT0.1S
        captureRole=generator
        captureDirection=OUT
        captureIoName=out
        sampleCount=1
        tapTtlSeconds=9
        """;
    var target = TargetLoader.loadSwarmAuthorization(file(config));
    assertEquals("fixtures/one", target.auth().bundle());
    assertEquals("generator", target.tap().role());
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadSwarmAuthorization(file(config.replace("captureIoName=out\n", ""))));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadSwarmAuthorization(file(config.replace("tapTtlSeconds=9", "tapTtlSeconds=8"))));
    assertThrows(IllegalArgumentException.class, () -> TargetLoader.loadProvisionedAuth(file(config)));
  }

  @Test void webAuthRequiresEveryExplicitObserverSetting() throws Exception {
    String settings = "redisConnectionId=chosen\nmockUsername=user\nmockPassword=pass\n";
    assertEquals("chosen", TargetLoader.loadWebAuth(file(TARGET + settings)).connectionId());
    for (String line : settings.split("\n")) {
      assertThrows(IllegalArgumentException.class,
          () -> TargetLoader.loadWebAuth(file(TARGET + settings.replace(line + "\n", ""))));
    }
  }
}
