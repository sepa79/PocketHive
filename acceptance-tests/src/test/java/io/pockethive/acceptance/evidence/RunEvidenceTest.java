package io.pockethive.acceptance.evidence;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunEvidenceTest {
  @TempDir Path reports;

  @Test void writesJsonAndClosesWithoutAnError() throws Exception {
    try (var evidence = new RunEvidence(reports, "successful")) {
      evidence.record("result", Map.of("value", 42));
      assertEquals(42, new ObjectMapper().readTree(evidence.directory().resolve("result.json").toFile())
          .required("value").intValue());
    }
  }
  @Test void retainsWriteFailuresUntilCloseWithoutBlockingSubsequentArtifacts() throws Exception {
    var evidence = new RunEvidence(reports, "write-failures");
    Files.createDirectory(evidence.directory().resolve("first.json"));
    Files.createDirectory(evidence.directory().resolve("second.json"));
    assertDoesNotThrow(() -> evidence.record("first", Map.of()));
    assertDoesNotThrow(() -> evidence.record("second", Map.of()));
    evidence.record("third", Map.of());
    assertTrue(Files.isRegularFile(evidence.directory().resolve("third.json")));
    var failure = assertThrows(IOException.class, evidence::close);
    assertTrue(failure.getMessage().contains("first.json"));
    assertEquals(1, failure.getSuppressed().length);
    assertTrue(failure.getSuppressed()[0].getMessage().contains("second.json"));
    assertDoesNotThrow(evidence::close);
  }
}
