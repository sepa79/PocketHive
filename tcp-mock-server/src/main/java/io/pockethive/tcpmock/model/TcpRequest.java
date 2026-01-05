package io.pockethive.tcpmock.model;

import java.time.Instant;
import java.util.Map;

public class TcpRequest {
    private final String id;
    private final String clientAddress;
    private final String message;
    private final Map<String, String> headers;
    private final String behavior;
    private final Instant timestamp;
    private final String response;

    public TcpRequest(String id, String clientAddress, String message, Map<String, String> headers,
                     String behavior, Instant timestamp, String response) {
        this.id = id;
        this.clientAddress = clientAddress;
        this.message = message;
        this.headers = headers;
        this.behavior = behavior;
        this.timestamp = timestamp;
        this.response = response;
    }

    public String getId() { return id; }
    public String getClientAddress() { return clientAddress; }
    public String getMessage() { return message; }
    public Map<String, String> getHeaders() { return headers; }
    public String getBehavior() { return behavior; }
    public Instant getTimestamp() { return timestamp; }
    public String getResponse() { return response; }
}
