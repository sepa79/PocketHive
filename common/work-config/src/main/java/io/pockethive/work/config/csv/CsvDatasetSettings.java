package io.pockethive.work.config.csv;

/**
 * Responsibility: retain immutable CSV settings validated by CsvDatasetParser.
 * Must not: decode configuration, load dataset files or own runtime state.
 * Contract: RESP-WORK-CSV-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-csv-settings.
 */
public final class CsvDatasetSettings {
    private final String filePath;
    private final double ratePerSec;
    private final boolean rotate;
    private final boolean skipHeader;
    private final java.util.regex.Pattern delimiter;
    private final java.nio.charset.Charset charset;
    private final long startupDelaySeconds;
    private final long tickIntervalMs;
    private final long initialDelayMs;

    CsvDatasetSettings(String filePath, double ratePerSec, boolean rotate, boolean skipHeader,
                       java.util.regex.Pattern delimiter, java.nio.charset.Charset charset,
                       long startupDelaySeconds, long tickIntervalMs, long initialDelayMs) {
        this.filePath = filePath;
        this.ratePerSec = ratePerSec;
        this.rotate = rotate;
        this.skipHeader = skipHeader;
        this.delimiter = delimiter;
        this.charset = charset;
        this.startupDelaySeconds = startupDelaySeconds;
        this.tickIntervalMs = tickIntervalMs;
        this.initialDelayMs = initialDelayMs;
    }

    public String filePath() { return filePath; }
    public double ratePerSec() { return ratePerSec; }
    public boolean rotate() { return rotate; }
    public boolean skipHeader() { return skipHeader; }
    public java.util.regex.Pattern delimiter() { return delimiter; }
    public java.nio.charset.Charset charset() { return charset; }
    public long startupDelaySeconds() { return startupDelaySeconds; }
    public long tickIntervalMs() { return tickIntervalMs; }
    public long initialDelayMs() { return initialDelayMs; }
}
