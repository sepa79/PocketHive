package io.pockethive.mcp.adapter.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.auth.client.AuthServiceServiceTokenProvider;
import io.pockethive.mcp.application.OwnerApiPort;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Responsibility: Execute authenticated HTTP requests against PocketHive owner-service ingress paths.
 * Must not: Own domain state transitions or duplicate owner-service contracts.
 * Contract: docs/mcp/README.md.
 */

@Component
public class OwnerApiClient implements OwnerApiPort {
    private static final String OWNER_REQUEST_REJECTED = "OWNER_REQUEST_REJECTED";
    private static final String OWNER_RESPONSE_INVALID = "OWNER_RESPONSE_INVALID";
    private static final String OWNER_RESULT_AMBIGUOUS = "OWNER_RESULT_AMBIGUOUS";
    private static final String OWNER_UNAVAILABLE = "OWNER_UNAVAILABLE";

    private final RestClient client;
    private final AuthServiceServiceTokenProvider tokens;
    private final ObjectMapper mapper;

    public OwnerApiClient(RestClient.Builder builder, PocketHiveMcpProperties properties,
                          AuthServiceServiceTokenProvider tokens, ObjectMapper mapper) {
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
        return authenticated(method, authorization -> zipOnce(method, path, archive, authorization));
    }

    private JsonNode zipOnce(HttpMethod method, String path, Path archive, String authorization) {
        return client.method(method)
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, authorization)
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
            throw new ToolExecutionException(OWNER_RESPONSE_INVALID,
                "Owner response was not valid JSON");
        }
    }

    private String responseText(HttpMethod method, String path, Object body) {
        return authenticated(method, authorization -> responseTextOnce(method, path, body, authorization));
    }

    private <T> T authenticated(HttpMethod method, Function<String, T> request) {
        try {
            return request.apply(tokens.getAuthorizationHeader());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
                try {
                    return request.apply(tokens.refreshAuthorizationHeader());
                } catch (RestClientResponseException refreshedException) {
                    throw ownerFailure(method, refreshedException);
                } catch (ResourceAccessException refreshedException) {
                    throw unavailableFailure(method);
                }
            }
            throw ownerFailure(method, exception);
        } catch (ResourceAccessException exception) {
            throw unavailableFailure(method);
        }
    }

    private String responseTextOnce(HttpMethod method, String path, Object body, String authorization) {
        RestClient.RequestBodySpec request = client.method(method)
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, authorization);
        if (body != null) {
            request.body(body);
        }
        return request.retrieve().body(String.class);
    }

    private static ToolExecutionException ownerFailure(HttpMethod method, RestClientResponseException exception) {
        String code = exception.getStatusCode().is5xxServerError() && method != HttpMethod.GET
            ? OWNER_RESULT_AMBIGUOUS
            : exception.getStatusCode().is5xxServerError() ? OWNER_UNAVAILABLE : OWNER_REQUEST_REJECTED;
        return new ToolExecutionException(code,
            "Owner returned HTTP " + exception.getStatusCode().value());
    }

    private static ToolExecutionException unavailableFailure(HttpMethod method) {
        String code = method == HttpMethod.GET ? OWNER_UNAVAILABLE : OWNER_RESULT_AMBIGUOUS;
        return new ToolExecutionException(code, "Owner response was unavailable");
    }
}
