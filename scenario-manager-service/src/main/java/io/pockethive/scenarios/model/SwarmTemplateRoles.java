package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwarmTemplateRoles {
    private Boolean generator;
    private Boolean moderator;
    private Boolean processor;
    private Boolean postProcessor;

    public SwarmTemplateRoles() {
    }

    public Boolean getGenerator() {
        return generator;
    }

    public void setGenerator(Boolean generator) {
        this.generator = generator;
    }

    public Boolean getModerator() {
        return moderator;
    }

    public void setModerator(Boolean moderator) {
        this.moderator = moderator;
    }

    public Boolean getProcessor() {
        return processor;
    }

    public void setProcessor(Boolean processor) {
        this.processor = processor;
    }

    public Boolean getPostProcessor() {
        return postProcessor;
    }

    public void setPostProcessor(Boolean postProcessor) {
        this.postProcessor = postProcessor;
    }
}
