package io.pockethive.swarmcontroller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

  public FileSwarmJournal(ObjectMapper mapper,
                          io.pockethive.swarmcontroller.config.SwarmControllerProperties properties,
                          @Value("${pockethive.journal.run-id}") String runId,
                          RuntimeFilesystemLayout layout) {
    this.mapper = Objects.requireNonNull(mapper, "mapper").findAndRegisterModules();
    Objects.requireNonNull(properties, "properties");
    this.swarmId = properties.getSwarmId();
    this.runId = requireNonBlank(runId, "runId");
    try {
      Path dir = layout.swarmRunDirectory(swarmId, this.runId);
      Files.createDirectories(dir);
      this.journalFile = dir.resolve("journal.ndjson");
      log.info("Swarm journal initialised at {}", journalFile);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to initialise swarm journal", e);
    }
  }

  @Override
  public void append(SwarmJournalEntry entry) {
    Objects.requireNonNull(entry, "entry");
    if (!swarmId.equals(entry.swarmId())) {
      throw new IllegalArgumentException(
          "Journal for swarm " + swarmId + " cannot accept entry for swarm " + entry.swarmId());
    }
    String json;
    try {
      json = mapper.writeValueAsString(entry);
    } catch (Exception e) {
      throw new SwarmJournalWriteException("Failed to serialise swarm journal entry", e);
    }
    lock.lock();
    try {
      Files.write(
          journalFile,
          (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new SwarmJournalWriteException("Failed to append to swarm journal at " + journalFile, e);
    } finally {
      lock.unlock();
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }
}
