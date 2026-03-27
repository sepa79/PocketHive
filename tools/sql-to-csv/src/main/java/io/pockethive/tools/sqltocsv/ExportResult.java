package io.pockethive.tools.sqltocsv;

/**
 * Immutable result of an export operation with timing diagnostics.
 */
public record ExportResult(
    int rowCount,
    int columnCount,
    long connectTimeMs,
    long queryTimeMs,
    long writeTimeMs,
    long totalTimeMs
) {
    public double throughputRowsPerSec() {
        return totalTimeMs > 0 ? (rowCount * 1000.0) / totalTimeMs : 0;
    }
}
