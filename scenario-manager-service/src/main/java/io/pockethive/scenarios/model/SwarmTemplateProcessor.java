package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SwarmTemplateProcessor {
    @NotBlank
    private String sutRef;

    private String endpointRef;

    private Map<String, String> headers;

    private String authProfileRef;

    public SwarmTemplateProcessor() {
    }

    public String getSutRef() {
        return sutRef;
    }

    public void setSutRef(String sutRef) {
        this.sutRef = sutRef;
    }

    public String getEndpointRef() {
        return endpointRef;
    }

    public void setEndpointRef(String endpointRef) {
        this.endpointRef = endpointRef;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getAuthProfileRef() {
        return authProfileRef;
    }

    public void setAuthProfileRef(String authProfileRef) {
        this.authProfileRef = authProfileRef;
    }
}
