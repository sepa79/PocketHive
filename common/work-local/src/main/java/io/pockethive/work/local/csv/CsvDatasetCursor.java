package io.pockethive.work.local.csv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

/**
 * Responsibility: load CSV records, format their JSON and own dataset cursor movement.
 * Must not: schedule input, dispatch work or own worker/configuration state.
 * Contract: RESP-WORK-CSV-DATASET — docs/architecture/runtime-responsibilities.md#resp-work-csv-dataset.
 */
public final class CsvDatasetCursor {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String inputName;
    private final Logger log;
    private String[] csvHeaders;
    private List<String[]> csvRows;
    private final AtomicInteger currentRowIndex = new AtomicInteger(0);

    public CsvDatasetCursor(String inputName, Logger log) {
        this.inputName = Objects.requireNonNull(inputName, "inputName");
        this.log = Objects.requireNonNull(log, "log");
    }

    public void load(CsvDatasetSettings settings) {
        Path path = Path.of(settings.filePath());
        if (!Files.exists(path)) {
            throw new IllegalStateException("CSV file not found: " + settings.filePath());
        }
        log.info("{} loading CSV (skipHeader={}, rotate={}): {}", inputName,
            settings.skipHeader(), settings.rotate(), settings.filePath());

        try (BufferedReader reader = Files.newBufferedReader(path, settings.charset())) {
            List<String[]> allRows = reader.lines()
                .filter(line -> !line.trim().isEmpty())
                .map(line -> settings.delimiter().split(line, -1))
                .toList();

            if (allRows.isEmpty()) {
                throw new IllegalStateException("CSV file is empty: " + settings.filePath());
            }

            if (settings.skipHeader()) {
                if (allRows.size() < 2) {
                    throw new IllegalStateException("CSV has only 1 row but skipHeader=true (need at least 2 rows)");
                }
                this.csvHeaders = allRows.get(0);
                this.csvRows = new ArrayList<>(allRows.subList(1, allRows.size()));
                log.info("{} loaded {} data rows with header: {}", inputName,
                    csvRows.size(), String.join(",", csvHeaders));
            } else {
                this.csvHeaders = null;
                this.csvRows = new ArrayList<>(allRows);
                log.info("{} loaded {} data rows (no header)", inputName, csvRows.size());
            }

            if (csvRows.isEmpty()) {
                throw new IllegalStateException("CSV has no data rows after header processing");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read CSV: " + settings.filePath(), ex);
        }
    }

    public String rowJson(int rowIndex) {
        String[] row = csvRows.get(rowIndex);
        try {
            ObjectNode json = MAPPER.createObjectNode();
            if (csvHeaders != null) {
                for (int i = 0; i < Math.min(csvHeaders.length, row.length); i++) {
                    json.put(csvHeaders[i].trim(), row[i].trim());
                }
            } else {
                for (int i = 0; i < row.length; i++) {
                    json.put("col" + i, row[i].trim());
                }
            }
            return MAPPER.writeValueAsString(json);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to convert CSV row to JSON", ex);
        }
    }

    public int nextRowIndex(boolean rotate) {
        int idx = currentRowIndex.getAndIncrement();
        log.info("{} getNextRowIndex: idx={}, size={}, rotate={}", inputName, idx, csvRows.size(), rotate);
        if (idx >= csvRows.size()) {
            if (rotate) {
                log.info("{} rotating: resetting to row 0", inputName);
                currentRowIndex.set(1);
                return 0;
            }
            log.info("{} exhausted at idx={}", inputName, idx);
            return -1;
        }
        return idx;
    }

    public int size() {
        return csvRows.size();
    }

    public int position() {
        return currentRowIndex.get();
    }

    public long remaining() {
        return Math.max(0, csvRows.size() - currentRowIndex.get());
    }
}
