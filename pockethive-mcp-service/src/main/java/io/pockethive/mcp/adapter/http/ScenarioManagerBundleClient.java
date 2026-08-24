package io.pockethive.mcp.adapter.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.mcp.application.OwnerCallAmbiguousException;
import io.pockethive.mcp.application.OwnerCallRejectedException;
import io.pockethive.mcp.application.OwnerScenarioProjection;
import io.pockethive.mcp.application.OwnerValidationResult;
import io.pockethive.mcp.application.ScenarioBundleOwnerPort;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public final class ScenarioManagerBundleClient implements ScenarioBundleOwnerPort {
    private static final String PREFIX = "/scenario-manager";
    private final OwnerApiClient client;

    public ScenarioManagerBundleClient(OwnerApiClient client) {
        this.client = client;
    }

    @Override
    public OwnerValidationResult validate(Path archive) {
        JsonNode result = postZip(PREFIX + "/validation/scenario-bundles", archive);
        return validation(result);
    }

    @Override
    public Object create(Path archive) {
        return postZip(PREFIX + "/scenarios/bundles", archive);
    }

    @Override
    public Object replace(String scenarioId, Path archive) {
        try {
            return client.putZip(PREFIX + "/scenarios/" + encode(scenarioId) + "/bundle", archive);
        } catch (RestClientResponseException exception) {
            throw new OwnerCallRejectedException("Scenario Manager rejected REPLACE", exception);
        } catch (ResourceAccessException exception) {
            throw new OwnerCallAmbiguousException("Scenario Manager REPLACE response unavailable", exception);
        }
    }

    @Override
    public OwnerScenarioProjection get(String scenarioId) {
        Object catalogue = client.get(PREFIX + "/api/templates");
        if (!(catalogue instanceof JsonNode root)) {
            throw new OwnerCallRejectedException("Scenario catalogue response invalid", null);
        }
        String bundleKey = findBundleKey(root, scenarioId);
        Object result = client.post(PREFIX + "/validation/scenario-bundles/existing?bundleKey=" + encode(bundleKey),
            Map.of());
        if (!(result instanceof JsonNode validation)) {
            throw new OwnerCallRejectedException("Scenario validation response invalid", null);
        }
        OwnerValidationResult parsed = validation(validation);
        return new OwnerScenarioProjection(parsed.scenarioId(), parsed.bundleContentDigest(), validation);
    }

    private JsonNode postZip(String path, Path archive) {
        try {
            return client.postZip(path, archive);
        } catch (RestClientResponseException exception) {
            throw new OwnerCallRejectedException("Scenario Manager rejected archive", exception);
        } catch (ResourceAccessException exception) {
            throw new OwnerCallAmbiguousException("Scenario Manager response unavailable", exception);
        }
    }

    private static OwnerValidationResult validation(JsonNode result) {
        if (result == null) {
            throw new OwnerCallRejectedException("Scenario validation response missing", null);
        }
        return new OwnerValidationResult(
            result.path("ok").asBoolean(false),
            result.path("scenarioId").asText(""),
            result.path("scenarioName").asText(""),
            result.path("validation").path("artifactDigest").asText(""),
            result);
    }

    private static String findBundleKey(JsonNode root, String scenarioId) {
        JsonNode values = root.isArray() ? root : root.path("templates");
        Iterator<JsonNode> entries = values.elements();
        while (entries.hasNext()) {
            JsonNode entry = entries.next();
            String id = entry.path("scenarioId").asText(entry.path("id").asText());
            if (scenarioId.equals(id) && !entry.path("bundleKey").asText().isBlank()) {
                return entry.path("bundleKey").asText();
            }
        }
        throw new OwnerCallRejectedException("Scenario reconciliation target not found", null);
    }

    private static String encode(String value) {
        return org.springframework.web.util.UriUtils.encodePathSegment(value,
            java.nio.charset.StandardCharsets.UTF_8);
    }
}
