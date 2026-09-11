package io.pockethive.work.local.csv;

import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputSettingsParseResult;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkerInputType;
import java.util.Map;

/**
 * Responsibility: adapt canonical CSV settings parsing to the neutral input parser port.
 * Must not: select inputs, retain accepted state or load CSV data.
 * Contract: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public final class CsvWorkInputSettingsParser implements WorkInputSettingsParser {
    private final CsvDatasetParser parser;

    public CsvWorkInputSettingsParser(CsvDatasetParser parser) {
        this.parser = java.util.Objects.requireNonNull(parser, "parser");
    }

    @Override
    public WorkerInputType type() { return WorkerInputType.CSV_DATASET; }

    @Override
    public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        var result = parser.validate(settings, path, mode);
        return new WorkInputSettingsParseResult(result.settings(), result.problems(), result.deferredPaths());
    }
}
