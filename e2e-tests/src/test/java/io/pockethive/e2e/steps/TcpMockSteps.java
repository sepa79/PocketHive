package io.pockethive.e2e.steps;

import io.cucumber.java.en.Given;
import io.pockethive.e2e.config.EnvironmentConfig;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class TcpMockSteps {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String tcpMockUrl = EnvironmentConfig.getTcpMockUrl();
    private final String username = EnvironmentConfig.getTcpMockUsername();
    private final String password = EnvironmentConfig.getTcpMockPassword();
    private static final Set<String> DEFAULT_MAPPING_IDS = Set.of("echo", "json", "default");

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
        Map<String, Object> response = restTemplate.exchange(
            tcpMockUrl + "/api/__admin/mappings",
            HttpMethod.GET,
            request,
            new ParameterizedTypeReference<Map<String, Object>>() {}
        ).getBody();
        if (response == null) {
            return;
        }
        Object mappingsObj = response.get("mappings");
        if (!(mappingsObj instanceof List<?> mappings)) {
            return;
        }
        for (Object mappingObj : mappings) {
            if (!(mappingObj instanceof Map<?, ?> mapping)) {
                continue;
            }
            Object idObj = mapping.get("id");
            if (idObj == null) {
                continue;
            }
            String id = idObj.toString();
            if (DEFAULT_MAPPING_IDS.contains(id)) {
                continue;
            }
            restTemplate.exchange(tcpMockUrl + "/api/__admin/mappings/" + id, HttpMethod.DELETE, request, Void.class);
        }
    }

    @Given("the TCP mock mapping {string} is removed")
    public void theTcpMockMappingIsRemoved(String mappingId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(username, password);

        HttpEntity<Void> request = new HttpEntity<>(headers);
        restTemplate.exchange(tcpMockUrl + "/api/__admin/mappings/" + mappingId, HttpMethod.DELETE, request, Void.class);
    }
}
