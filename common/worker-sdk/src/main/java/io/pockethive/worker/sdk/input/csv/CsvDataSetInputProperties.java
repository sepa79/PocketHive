package io.pockethive.worker.sdk.input.csv;

import io.pockethive.work.local.csv.CsvDatasetParser;
import io.pockethive.work.local.csv.CsvDatasetSettings;
import io.pockethive.work.config.binding.WorkInputConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: bind raw CSV startup fields and delegate their validation to work-config.
 * Must not: coerce CSV values, own worker enablement or read dataset files.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public final class CsvDataSetInputProperties implements WorkInputConfig {
    private Object filePath;
    private Object ratePerSec;
    private Object rotate;
    private Object skipHeader;
    private Object delimiter;
    private Object charset;
    private Object startupDelaySeconds;
    private Object tickIntervalMs;

    public Object getFilePath() { return filePath; }
    public void setFilePath(Object value) { filePath = value; }
    public Object getRatePerSec() { return ratePerSec; }
    public void setRatePerSec(Object value) { ratePerSec = value; }
    public Object getRotate() { return rotate; }
    public void setRotate(Object value) { rotate = value; }
    public Object getSkipHeader() { return skipHeader; }
    public void setSkipHeader(Object value) { skipHeader = value; }
    public Object getDelimiter() { return delimiter; }
    public void setDelimiter(Object value) { delimiter = value; }
    public Object getCharset() { return charset; }
    public void setCharset(Object value) { charset = value; }
    public Object getStartupDelaySeconds() { return startupDelaySeconds; }
    public void setStartupDelaySeconds(Object value) { startupDelaySeconds = value; }
    public Object getTickIntervalMs() { return tickIntervalMs; }
    public void setTickIntervalMs(Object value) { tickIntervalMs = value; }

    public CsvDatasetSettings settings() {
        return new CsvDatasetParser().parse(rawSettings(), CsvDatasetParser.PATH);
    }

    public double ratePerSec() { return settings().ratePerSec(); }
    public long startupDelaySeconds() { return settings().startupDelaySeconds(); }
    public long tickIntervalMs() { return settings().tickIntervalMs(); }

    @Override
    public void validateConfigured(String prefix) {
        new CsvDatasetParser().parse(rawSettings(), prefix);
    }

    private Map<String, Object> rawSettings() {
        var fields = new LinkedHashMap<String, Object>();
        fields.put(CsvDatasetParser.FILE_PATH, filePath);
        fields.put(io.pockethive.work.config.input.InputRateParser.FIELD, ratePerSec);
        fields.put(CsvDatasetParser.ROTATE, rotate);
        fields.put(CsvDatasetParser.SKIP_HEADER, skipHeader);
        fields.put(CsvDatasetParser.DELIMITER, delimiter);
        fields.put(CsvDatasetParser.CHARSET, charset);
        fields.put(io.pockethive.work.config.input.InputScheduleField.STARTUP_DELAY_SECONDS.key(), startupDelaySeconds);
        fields.put(io.pockethive.work.config.input.InputScheduleField.TICK_INTERVAL_MS.key(), tickIntervalMs);
        return fields;
    }
}
