package io.pockethive.orchestrator.infra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.app.SwarmJournalFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Responsibility: observe journal directories and decode the layout-owned swarm journal files.
 * Must not: select the registry's active run, mutate files or own retention and HTTP responses.
 * Contract: RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal.
 */
@Component
public class FileSwarmJournalReader implements SwarmJournalFiles {
    private static final Logger log = LoggerFactory.getLogger(FileSwarmJournalReader.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final ObjectMapper json;
    private final RuntimeFilesystemLayout layout;

    public FileSwarmJournalReader(ObjectMapper json, RuntimeFilesystemLayout layout) {
        this.json = json;
        this.layout = layout;
    }

    @Override
    public List<Map<String, Object>> read(String swarmId, String runId, String severityFilter) {
        Path journal = layout.swarmJournalFile(swarmId, runId);
        if (!Files.isRegularFile(journal)) {
            return null;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(journal);
        } catch (Exception ex) {
            log.warn("Unable to read journal file {}: {}", journal, ex.getMessage());
            return null;
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> entry = json.readValue(trimmed, MAP_TYPE);
                if (matchesSeverityFilter(entry, severityFilter)) {
                    result.add(entry);
                }
            } catch (Exception ex) {
                log.warn("Skipping malformed journal line in {}: {}", journal, ex.getMessage());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String latestRunDirectory(String swarmId) {
        try {
            Path base = layout.swarmRoot(swarmId);
            if (!Files.isDirectory(base)) {
                return null;
            }
            try (var stream = Files.list(base)) {
                return stream
                    .filter(Files::isDirectory)
                    .max(Comparator.comparing(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toMillis();
                        } catch (Exception e) {
                            return 0L;
                        }
                    }))
                    .map(path -> path.getFileName().toString())
                    .orElse(null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesSeverityFilter(Map<String, Object> entry, String severityFilter) {
        if (severityFilter == null) {
            return true;
        }
        Object severity = entry.get("severity");
        return severity instanceof String value && severityFilter.equals(value.trim().toUpperCase(Locale.ROOT));
    }

}
