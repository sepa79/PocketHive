package io.pockethive.acceptance.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Responsibility: write per-test artifacts and report write failures after resource cleanup.
 * Must not: interrupt resource cleanup, log credentials or calculate test/domain outcomes.
 * Contract: RESP-ACCEPTANCE-EVIDENCE — docs/architecture/acceptance-tests.md#resp-acceptance-evidence.
 */
public final class RunEvidence implements AutoCloseable {
  private final Path directory;
  private IOException writeFailure;
  private final ObjectMapper json = JsonMapper.builder().findAndAddModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
  public RunEvidence(Path root, String testName) throws IOException {
    Files.createDirectories(root);
    directory = Files.createDirectory(root.resolve(testName + "-" + UUID.randomUUID()));
  }
  public Path directory() { return directory; }
  public void record(String name, Object value) {
    if (!name.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid artifact name");
    Path artifact = directory.resolve(name + ".json");
    try {
      json.writerWithDefaultPrettyPrinter().writeValue(artifact.toFile(), value);
    } catch (IOException failure) {
      var reported = new IOException("Cannot write acceptance evidence: " + artifact, failure);
      if (writeFailure == null) writeFailure = reported;
      else writeFailure.addSuppressed(reported);
    }
  }
  @Override public void close() throws IOException {
    if (writeFailure != null) {
      IOException failure = writeFailure;
      writeFailure = null;
      throw failure;
    }
  }
}
