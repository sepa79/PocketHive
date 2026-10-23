package io.pockethive.orchestrator.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.journal.api.PlannerRunLink;
import java.net.URI;
import java.net.HttpURLConnection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: read the configured Planner catalogue and resolve exact selections at its boundary.
 * Must not: follow redirects, retain bearer sessions, generate Planner IDs or write either database.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
@Component
public final class PocketPlannerClient {
    private final String baseUrl;
    private final ObjectMapper json;

    public PocketPlannerClient(@Value("${pockethive.planner.base-url:}") String baseUrl, ObjectMapper json) {
        this.baseUrl = baseUrl;
        this.json = json;
    }

    public JsonNode catalogue(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A PocketHive session is required");
        }
        URI base;
        try {
            base = URI.create(baseUrl);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure POCKETHIVE_PLANNER_BASE_URL");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(base.toString().replaceAll("/+$", "") + "/api/integration/catalogue").toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("Authorization", authorization);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) {
                throw new ResponseStatusException(HttpStatus.valueOf(status), "Planner denied the session");
            }
            if (status != 200) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Planner catalogue is unavailable");
            }
            try (var body = connection.getInputStream()) {
                byte[] bytes = body.readNBytes(2 * 1024 * 1024 + 1);
                if (bytes.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Catalogue too large");
                JsonNode result = json.readTree(bytes);
                if (result == null || result.path("schemaVersion").asInt(-1) != 1 || !result.path("instanceId").isTextual()
                    || !result.path("revision").isIntegralNumber() || result.path("revision").asLong() < 0 || !result.path("plans").isArray()) {
                    throw new IllegalArgumentException("Invalid catalogue");
                }
                return result;
            }
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Planner returned unavailable or invalid catalogue data");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public PlannerRunLink resolve(PlannerSelection selection, String authorization) {
        JsonNode catalogue = catalogue(authorization);
        if (!catalogue.path("instanceId").asText().equals(selection.instanceId())
            || catalogue.path("revision").asLong() != selection.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Planner changed. Reload and select the Activity again");
        }
        JsonNode plan = find(catalogue.path("plans"), selection.planId());
        find(plan.path("streams"), selection.streamId());
        JsonNode activity = find(plan.path("activities"), selection.activityId());
        if (!activity.path("streamId").asText().equals(selection.streamId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activity does not belong to the selected Stream");
        }
        try {
            return new PlannerRunLink(plan.path("testPlan").asText(), activity.path("tag").asText());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Planner returned invalid metadata references");
        }
    }

    private static JsonNode find(JsonNode items, String id) {
        if (items.isArray()) {
            for (JsonNode item : items) {
                if (item.path("id").asText().equals(id)) return item;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Planner selection");
    }
}
