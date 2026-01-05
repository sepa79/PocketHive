package io.pockethive.tcpmock.model;

import java.time.Instant;

public class RequestVerification {
    private final long requestId;
    private final String mappingId;
    private final String message;
    private final Instant timestamp;

    public RequestVerification(long requestId, String mappingId, String message, Instant timestamp) {
        this.requestId = requestId;
        this.mappingId = mappingId;
        this.message = message;
        this.timestamp = timestamp;
    }

    public long getRequestId() { return requestId; }
    public String getMappingId() { return mappingId; }
    public String getMessage() { return message; }
    public Instant getTimestamp() { return timestamp; }
}
