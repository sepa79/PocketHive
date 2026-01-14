package io.pockethive.tcpmock.model;

public class ProcessedResponse {
    private final String response;
    private final String delimiter;
    private final Integer delayMs;
    private final FaultType fault;
    private final String proxyTarget;
    
    public ProcessedResponse(String response, String delimiter) {
        this(response, delimiter, null, null, null);
    }
    
    public ProcessedResponse(String response, String delimiter, Integer delayMs, FaultType fault, String proxyTarget) {
        this.response = response;
        this.delimiter = delimiter != null ? delimiter : "\n";
        this.delayMs = delayMs;
        this.fault = fault;
        this.proxyTarget = proxyTarget;
    }
    
    public String getResponse() { return response; }
    public String getDelimiter() { return delimiter; }
    public Integer getDelayMs() { return delayMs; }
    public FaultType getFault() { return fault; }
    public String getProxyTarget() { return proxyTarget; }
    public boolean hasFault() { return fault != null; }
    public boolean hasProxy() { return proxyTarget != null; }
    public boolean hasDelay() { return delayMs != null && delayMs > 0; }
    
    public enum FaultType {
        CONNECTION_RESET,
        EMPTY_RESPONSE,
        MALFORMED_RESPONSE,
        RANDOM_DATA
    }
}
