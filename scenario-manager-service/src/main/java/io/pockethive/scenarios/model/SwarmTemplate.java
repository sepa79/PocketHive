package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwarmTemplate {
    @NotBlank
    private String id;

    @NotBlank
    private String name;

    @Valid
    private SwarmTemplateRoles roles;

    @Valid
    private SwarmTemplateGenerator generator;

    private Map<String, Object> moderator;

    @Valid
    @NotNull
    private SwarmTemplateProcessor processor;

    private Map<String, Object> postProcessor;

    public SwarmTemplate() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SwarmTemplateRoles getRoles() {
        return roles;
    }

    public void setRoles(SwarmTemplateRoles roles) {
        this.roles = roles;
    }

    public SwarmTemplateGenerator getGenerator() {
        return generator;
    }

    public void setGenerator(SwarmTemplateGenerator generator) {
        this.generator = generator;
    }

    public Map<String, Object> getModerator() {
        return moderator;
    }

    public void setModerator(Map<String, Object> moderator) {
        this.moderator = moderator;
    }

    public SwarmTemplateProcessor getProcessor() {
        return processor;
    }

    public void setProcessor(SwarmTemplateProcessor processor) {
        this.processor = processor;
    }

    public Map<String, Object> getPostProcessor() {
        return postProcessor;
    }

    public void setPostProcessor(Map<String, Object> postProcessor) {
        this.postProcessor = postProcessor;
    }
}
