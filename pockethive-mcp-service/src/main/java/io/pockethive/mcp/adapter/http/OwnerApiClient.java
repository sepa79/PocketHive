package io.pockethive.mcp.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.application.OwnerApiPort;
import io.pockethive.mcp.application.ToolExecutionException;
import java.util.Map;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OwnerApiClient implements OwnerApiPort {
    private final RestClient client;
    private final DownstreamTokenProvider tokens;
    private final ObjectMapper mapper;

    public OwnerApiClient(RestClient.Builder builder, PocketHiveMcpProperties properties,
                          DownstreamTokenProvider tokens, ObjectMapper mapper) {
        this.client = builder.baseUrl(properties.ownerApiBase().toString()).build();
        this.tokens = tokens;
        this.mapper = mapper;
    }

    @Override
    public Object get(String path) {
        return exchange(HttpMethod.GET, path, null);
    }

    @Override
    public String getText(String path) {
        String response = responseText(HttpMethod.GET, path, null);
        return response == null ? "" : response;
    }

    @Override
    public Object post(String path, Object body) {
        return exchange(HttpMethod.POST, path, body);
    }

    @Override
    public Object delete(String path) {
        return exchange(HttpMethod.DELETE, path, null);
    }

    public JsonNode postZip(String path, Path archive) {
        return zip(HttpMethod.POST, path, archive);
    }

    public JsonNode putZip(String path, Path archive) {
        return zip(HttpMethod.PUT, path, archive);
    }

    private JsonNode zip(HttpMethod method, String path, Path archive) {
        return client.method(method)
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.bearerToken())
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(new FileSystemResource(archive))
            .retrieve()
            .body(JsonNode.class);
    }

    private Object exchange(HttpMethod method, String path, Object body) {
        String response = responseText(method, path, body);
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode json = mapper.readTree(response);
            return json;
        } catch (JsonProcessingException exception) {
            throw new ToolExecutionException("OWNER_RESPONSE_INVALID",
                "Owner response was not valid JSON");
        }
    }

    private String responseText(HttpMethod method, String path, Object body) {
        RestClient.RequestBodySpec request = client.method(method)
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.bearerToken());
        if (body != null) {
            request.body(body);
        }
        String response;
        try {
            response = request.retrieve().body(String.class);
        } catch (RestClientResponseException exception) {
            String code = exception.getStatusCode().is5xxServerError() && method != HttpMethod.GET
                ? "OWNER_RESULT_AMBIGUOUS"
                : exception.getStatusCode().is5xxServerError() ? "OWNER_UNAVAILABLE" : "OWNER_REQUEST_REJECTED";
            throw new ToolExecutionException(code,
                "Owner returned HTTP " + exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            String code = method == HttpMethod.GET ? "OWNER_UNAVAILABLE" : "OWNER_RESULT_AMBIGUOUS";
            throw new ToolExecutionException(code, "Owner response was unavailable");
        }
        return response;
    }
}
