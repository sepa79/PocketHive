package io.pockethive.worker.sdk.input.csv;

import io.pockethive.work.config.input.InputRateParser;
import io.pockethive.work.config.input.InputScheduleField;
import io.pockethive.work.config.input.InputScheduleParser;
import io.pockethive.work.config.WorkerInputType;

import io.pockethive.worker.sdk.config.WorkInputConfig;

/**
 * Responsibility: bind CSV startup settings and delegate rate/timing/limit validation to work-config.
 * Must not: implement rate/timing/limit constraints or read dataset files.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-INPUT-RATE and RESP-WORK-INPUT-SCHEDULE:
 * docs/architecture/runtime-responsibilities.md#resp-work-input-schedule. Remaining CSV settings are B02 debt.
 */
public final class CsvDataSetInputProperties implements WorkInputConfig {

    private String filePath;
    private Object ratePerSec;
    private Boolean rotate;
    private Boolean skipHeader;
    private String delimiter;
    private String charset;
    private Object startupDelaySeconds;
    private Object tickIntervalMs;
    private boolean enabled = true;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public Object getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(Object ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public double ratePerSec() {
        return new InputRateParser().parse(ratePerSec, InputRateParser.CSV_PATH);
    }

    public boolean isRotate() {
        return requirePresent(rotate, "rotate");
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    public boolean isSkipHeader() {
        return requirePresent(skipHeader, "skipHeader");
    }

    public void setSkipHeader(boolean skipHeader) {
        this.skipHeader = skipHeader;
    }

    public String getDelimiter() {
        return requireNonBlank(delimiter, "delimiter");
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public String getCharset() {
        return requireNonBlank(charset, "charset");
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public Object getStartupDelaySeconds() {
        return startupDelaySeconds;
    }

    public void setStartupDelaySeconds(Object startupDelaySeconds) {
        this.startupDelaySeconds = startupDelaySeconds;
    }

    public long startupDelaySeconds() {
        return new InputScheduleParser().parse(startupDelaySeconds, InputScheduleField.STARTUP_DELAY_SECONDS,
            InputScheduleField.STARTUP_DELAY_SECONDS.path(WorkerInputType.CSV_DATASET));
    }

    public Object getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(Object tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public long tickIntervalMs() {
        return new InputScheduleParser().parse(tickIntervalMs, InputScheduleField.TICK_INTERVAL_MS,
            InputScheduleField.TICK_INTERVAL_MS.path(WorkerInputType.CSV_DATASET));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getInitialDelayMs() {
        return new InputScheduleParser().startupDelayMillis(startupDelaySeconds,
            InputScheduleField.STARTUP_DELAY_SECONDS.path(WorkerInputType.CSV_DATASET));
    }

    @Override
    public void validateConfigured(String prefix) {
        requireNonBlank(filePath, prefix + ".filePath");
        new InputRateParser().parse(ratePerSec, prefix + "." + InputRateParser.FIELD);
        requirePresent(rotate, prefix + ".rotate");
        requirePresent(skipHeader, prefix + ".skipHeader");
        requireNonBlank(delimiter, prefix + ".delimiter");
        requireNonBlank(charset, prefix + ".charset");
        new InputScheduleParser().parse(startupDelaySeconds, InputScheduleField.STARTUP_DELAY_SECONDS,
            prefix + "." + InputScheduleField.STARTUP_DELAY_SECONDS.key());
        new InputScheduleParser().parse(tickIntervalMs, InputScheduleField.TICK_INTERVAL_MS,
            prefix + "." + InputScheduleField.TICK_INTERVAL_MS.key());
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

}
