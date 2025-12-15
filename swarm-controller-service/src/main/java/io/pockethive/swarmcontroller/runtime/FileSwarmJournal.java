package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * File-backed implementation of {@link SwarmJournal}.
 * <p>
 * Writes append-only JSON lines into a per-swarm journal file located under
 * the configured runtime root directory. The initial format is intentionally
 * simple so it can later be reused for recording/replay.
 */
@Component
@ConditionalOnProperty(name = "pockethive.journal.sink", havingValue = "file", matchIfMissing = true)
public class FileSwarmJournal implements SwarmJournal {

  private static final Logger log = LoggerFactory.getLogger(FileSwarmJournal.class);

  private final ObjectMapper mapper;
  private final String swarmId;
  private final String runId;
  private final Path journalFile;
  private final Lock lock = new ReentrantLock();
  private final boolean enabled;

  public FileSwarmJournal(ObjectMapper mapper,
                          io.pockethive.swarmcontroller.config.SwarmControllerProperties properties,
                          @Value("${pockethive.journal.run-id}") String runId,
                          @Value("${pockethive.scenarios.runtime-root:}") String runtimeRoot) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    Objects.requireNonNull(properties, "properties");
    this.swarmId = properties.getSwarmId();
    this.runId = sanitizeSegment(requireNonBlank(runId, "runId"));
    Path file = null;
    boolean ok = false;
    try {
      Path root = resolveRuntimeRoot(runtimeRoot);
      Path dir = root.resolve(sanitizeSegment(swarmId)).resolve(runId).normalize();
      if (!dir.startsWith(root)) {
        throw new IllegalArgumentException("Invalid swarmId for runtime directory");
      }
      Files.createDirectories(dir);
      file = dir.resolve("journal.ndjson");
      ok = true;
      log.info("Swarm journal initialised at {}", file);
    } catch (Exception e) {
      log.warn("Swarm journal disabled; unable to initialise runtime directory: {}", e.getMessage());
    }
    this.journalFile = file;
    this.enabled = ok;
  }

  @Override
  public void append(SwarmJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    if (!enabled || journalFile == null) {
      return;
    }
    if (!swarmId.equals(entry.swarmId())) {
      // Enforce per-swarm isolation; journal is scoped to a single swarm.
      return;
    }
    String json;
    try {
      json = mapper.writeValueAsString(entry);
    } catch (Exception e) {
      log.warn("Failed to serialise swarm journal entry: {}", e.getMessage());
      return;
    }
    lock.lock();
    try {
      Files.write(
          journalFile,
          (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      log.warn("Failed to append to swarm journal at {}: {}", journalFile, e.getMessage());
    } finally {
      lock.unlock();
    }
  }

  private static Path resolveRuntimeRoot(String runtimeRoot) {
    if (runtimeRoot == null || runtimeRoot.isBlank()) {
      throw new IllegalArgumentException("pockethive.scenarios.runtime-root must not be blank");
    }
    return Paths.get(runtimeRoot).toAbsolutePath().normalize();
  }

  private static String sanitizeSegment(String segment) {
    if (segment == null || segment.isBlank()) {
      throw new IllegalArgumentException("swarmId must not be null or blank");
    }
    String cleaned = Paths.get(segment).getFileName().toString();
    if (!cleaned.equals(segment) || cleaned.contains("..") || cleaned.isBlank()) {
      throw new IllegalArgumentException("Invalid swarmId");
    }
    return cleaned;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
