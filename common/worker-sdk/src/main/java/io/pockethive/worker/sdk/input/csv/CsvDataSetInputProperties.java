package io.pockethive.worker.sdk.input.csv;

import io.pockethive.worker.sdk.config.WorkInputConfig;

public final class CsvDataSetInputProperties implements WorkInputConfig {

    private String filePath;
    private double ratePerSec = 1.0;
    private boolean rotate = false;
    private boolean skipHeader = true;
    private String delimiter = ",";
    private String charset = "UTF-8";
    private long startupDelaySeconds = 0L;
    private long tickIntervalMs = 1000L;
    private boolean enabled = true;

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public double getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = ratePerSec;
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    public boolean isSkipHeader() {
        return skipHeader;
    }

    public void setSkipHeader(boolean skipHeader) {
        this.skipHeader = skipHeader;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(String delimiter) {
        this.delimiter = delimiter;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public long getStartupDelaySeconds() {
        return startupDelaySeconds;
    }

    public void setStartupDelaySeconds(long startupDelaySeconds) {
        this.startupDelaySeconds = startupDelaySeconds;
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
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
        return startupDelaySeconds * 1000L;
    }
}
