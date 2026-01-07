package io.pockethive.e2e.steps;

import io.cucumber.java.en.Given;
import io.pockethive.e2e.config.EnvironmentConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

public class TcpMockSteps {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String tcpMockUrl = EnvironmentConfig.getTcpMockUrl();
    private final String username = EnvironmentConfig.getTcpMockUsername();
    private final String password = EnvironmentConfig.getTcpMockPassword();

    @Given("the TCP mock server has the following mappings:")
    public void theTcpMockServerHasTheFollowingMappings(String mappingsJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(username, password);

        HttpEntity<String> request = new HttpEntity<>(mappingsJson, headers);
        restTemplate.postForEntity(tcpMockUrl + "/api/mappings", request, String.class);
    }

    @Given("the TCP mock server is cleared")
    public void theTcpMockServerIsCleared() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        restTemplate.delete(tcpMockUrl + "/api/mappings");
    }
}
