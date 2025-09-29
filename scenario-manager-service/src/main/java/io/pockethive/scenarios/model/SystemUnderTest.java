package io.pockethive.scenarios.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemUnderTest {
    @NotBlank
    private String id;

    @NotBlank
    private String name;

    private List<String> baseUrls;

    @Valid
    private List<SystemUnderTestEndpoint> endpoints;

    private String authProfileRef;

    public SystemUnderTest() {
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

    public List<String> getBaseUrls() {
        return baseUrls;
    }

    public void setBaseUrls(List<String> baseUrls) {
        this.baseUrls = baseUrls;
    }

    public List<SystemUnderTestEndpoint> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<SystemUnderTestEndpoint> endpoints) {
        this.endpoints = endpoints;
    }

    public String getAuthProfileRef() {
        return authProfileRef;
    }

    public void setAuthProfileRef(String authProfileRef) {
        this.authProfileRef = authProfileRef;
    }
}
