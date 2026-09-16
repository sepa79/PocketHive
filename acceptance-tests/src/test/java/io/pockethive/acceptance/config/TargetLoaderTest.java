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
    assertEquals(folder.resolve("reports"), target.evidenceDirectory());
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
}
