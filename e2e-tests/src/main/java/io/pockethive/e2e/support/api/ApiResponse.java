package io.pockethive.e2e.support.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Captures the last ingress API exchange in a form that step definitions can assert on.
 */
public record ApiResponse(int status, String body, JsonNode jsonBody) {
}
