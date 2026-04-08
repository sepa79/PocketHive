package io.pockethive.tcpmock.model;

import java.util.Map;

public class MessageTypeMapping {

    /**
     * Declares the wire framing profile for this mapping.
     * When set on the highest-priority enabled mapping, the TCP server uses it
     * instead of auto-detecting the protocol from the first bytes.
     */
    public enum WireProfile {
        /** Auto-detect from first bytes (default). */
        AUTO,
        /** Newline-delimited text (\n). */
        LINE,
        /** Custom text delimiter from requestDelimiter field. */
        DELIMITER,
        /** 2-byte big-endian length prefix (MC_2BYTE_LEN_BIN_BITMAP). */
        LENGTH_PREFIX_2B,
        /** 4-byte big-endian length prefix. */
        LENGTH_PREFIX_4B,
        /** Fixed-length frames; frame size from fixedFrameLength field. */
        FIXED_LENGTH,
        /** STX (0x02) ... ETX (0x03) binary framing. */
        STX_ETX,
        /** Write only — no response expected. */
        FIRE_FORGET
    }

    private String id;
    private String requestPattern;
    private Map<String, Object> advancedMatching;
    private String responseTemplate;
    private String requestDelimiter = "\n";
    private String responseDelimiter = "\n";
    private Integer fixedDelayMs;
    private String description;
    private int priority = 1;
    private boolean enabled = true;
    private String scenarioName;
    private String requiredScenarioState;
    private String newScenarioState;
    private int matchCount = 0;
    private ConditionalResponse conditionalResponse;
    /** Wire framing profile. Null and AUTO are equivalent — triggers auto-detection. */
    private WireProfile wireProfile;
    /** Frame size in bytes, used only when wireProfile=FIXED_LENGTH. */
    private Integer fixedFrameLength;

    public MessageTypeMapping() {}

    public MessageTypeMapping(String id, String requestPattern, String responseTemplate, String description) {
        this.id = id;
        this.requestPattern = requestPattern;
        this.responseTemplate = responseTemplate;
        this.description = description;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRequestPattern() { return requestPattern; }
    public void setRequestPattern(String requestPattern) { this.requestPattern = requestPattern; }
    public Map<String, Object> getAdvancedMatching() { return advancedMatching; }
    public void setAdvancedMatching(Map<String, Object> advancedMatching) { this.advancedMatching = advancedMatching; }
    public String getResponseTemplate() { return responseTemplate; }
    public void setResponseTemplate(String responseTemplate) { this.responseTemplate = responseTemplate; }
    public String getRequestDelimiter() { return requestDelimiter; }
    public void setRequestDelimiter(String requestDelimiter) { this.requestDelimiter = requestDelimiter; }
    public String getResponseDelimiter() { return responseDelimiter; }
    public void setResponseDelimiter(String responseDelimiter) { this.responseDelimiter = responseDelimiter; }
    public Integer getFixedDelayMs() { return fixedDelayMs; }
    public void setFixedDelayMs(Integer fixedDelayMs) { this.fixedDelayMs = fixedDelayMs; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getScenarioName() { return scenarioName; }
    public void setScenarioName(String scenarioName) { this.scenarioName = scenarioName; }
    public String getRequiredScenarioState() { return requiredScenarioState; }
    public void setRequiredScenarioState(String requiredScenarioState) { this.requiredScenarioState = requiredScenarioState; }
    public String getNewScenarioState() { return newScenarioState; }
    public void setNewScenarioState(String newScenarioState) { this.newScenarioState = newScenarioState; }
    public int getMatchCount() { return matchCount; }
    public void setMatchCount(int matchCount) { this.matchCount = matchCount; }
    public void incrementMatchCount() { this.matchCount++; }
    public ConditionalResponse getConditionalResponse() { return conditionalResponse; }
    public void setConditionalResponse(ConditionalResponse conditionalResponse) { this.conditionalResponse = conditionalResponse; }
    public WireProfile getWireProfile() { return wireProfile; }
    public void setWireProfile(WireProfile wireProfile) { this.wireProfile = wireProfile; }
    public Integer getFixedFrameLength() { return fixedFrameLength; }
    public void setFixedFrameLength(Integer fixedFrameLength) { this.fixedFrameLength = fixedFrameLength; }

    public static class ConditionalResponse {
        private String condition;
        private String successResponse;
        private String failureResponse;

        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
        public String getSuccessResponse() { return successResponse; }
        public void setSuccessResponse(String successResponse) { this.successResponse = successResponse; }
        public String getFailureResponse() { return failureResponse; }
        public void setFailureResponse(String failureResponse) { this.failureResponse = failureResponse; }
    }
}
