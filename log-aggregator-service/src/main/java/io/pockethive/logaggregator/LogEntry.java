package io.pockethive.logaggregator;

public record LogEntry(String service, String traceId, String level, String message, String timestamp) {}
