package io.pockethive.worker.sdk.input.csv;

import io.pockethive.work.config.input.InputRateParser;

import io.pockethive.worker.sdk.config.WorkInputConfig;

/**
 * Responsibility: bind CSV startup settings and delegate rate validation to work-config.
 * Must not: implement input-rate constraints or read dataset files.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 * Consumes RESP-WORK-INPUT-RATE; remaining CSV settings are B02 debt.
 */
public final class CsvDataSetInputProperties implements WorkInputConfig {

    private static final long MIN_STARTUP_DELAY_SECONDS = 0L;
    private static final long MIN_TICK_INTERVAL_MS = 100L;

    private String filePath;
    private Object ratePerSec;
    private Boolean rotate;
    private Boolean skipHeader;
    private String delimiter;
    private String charset;
    private Long startupDelaySeconds;
    private Long tickIntervalMs;
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

    public long getStartupDelaySeconds() {
        return requireStartupDelaySeconds(startupDelaySeconds, "startupDelaySeconds");
    }

    public void setStartupDelaySeconds(long startupDelaySeconds) {
        this.startupDelaySeconds = startupDelaySeconds;
    }

    public long getTickIntervalMs() {
        return requireTickIntervalMs(tickIntervalMs, "tickIntervalMs");
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = tickIntervalMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getInitialDelayMs() {
        return getStartupDelaySeconds() * 1000L;
    }

    @Override
    public void validateConfigured(String prefix) {
        requireNonBlank(filePath, prefix + ".filePath");
        new InputRateParser().parse(ratePerSec, prefix + "." + InputRateParser.FIELD);
        requirePresent(rotate, prefix + ".rotate");
        requirePresent(skipHeader, prefix + ".skipHeader");
        requireNonBlank(delimiter, prefix + ".delimiter");
        requireNonBlank(charset, prefix + ".charset");
        requireStartupDelaySeconds(startupDelaySeconds, prefix + ".startupDelaySeconds");
        requireTickIntervalMs(tickIntervalMs, prefix + ".tickIntervalMs");
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

    private static long requireStartupDelaySeconds(Long value, String name) {
        long delay = requirePresent(value, name);
        if (delay < MIN_STARTUP_DELAY_SECONDS) {
            throw new IllegalStateException(name + " must be >= " + MIN_STARTUP_DELAY_SECONDS);
        }
        return delay;
    }

    private static long requireTickIntervalMs(Long value, String name) {
        long interval = requirePresent(value, name);
        if (interval < MIN_TICK_INTERVAL_MS) {
            throw new IllegalStateException(name + " must be >= " + MIN_TICK_INTERVAL_MS);
        }
        return interval;
    }
}
