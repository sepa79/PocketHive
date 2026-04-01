package io.pockethive.tcpmock.model;

import java.util.Map;

public class MessageTypeMapping {
    private String id;
    private String requestPattern;
    private Map<String, Object> advancedMatching; // For JSON/XML/header matching
    private String responseTemplate;
    private String responseDelimiter = "\n"; // Default to newline
    private Integer fixedDelayMs; // Per-mapping delay
    private String description;
    private int priority = 1;
    private boolean enabled = true;
    private String scenarioName;
    private String requiredScenarioState;
    private String newScenarioState;
    private int matchCount = 0;
    private ConditionalResponse conditionalResponse;

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
