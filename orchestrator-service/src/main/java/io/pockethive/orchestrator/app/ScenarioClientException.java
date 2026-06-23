package io.pockethive.orchestrator.app;

public final class ScenarioClientException extends RuntimeException {
    private static final int MESSAGE_BODY_LIMIT = 500;

    private final String label;
    private final int statusCode;
    private final String responseBody;
    private final String contentType;

    public ScenarioClientException(String label, int statusCode, String responseBody, String contentType) {
        super(message(label, statusCode, responseBody));
        this.label = label;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.contentType = contentType;
    }

    public String label() {
        return label;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public String contentType() {
        return contentType;
    }

    private static String message(String label, int statusCode, String responseBody) {
        String base = "%s status %d".formatted(label, statusCode);
        if (responseBody == null || responseBody.isBlank()) {
            return base;
        }
        return base + ": " + preview(responseBody);
    }

    private static String preview(String responseBody) {
        String normalized = responseBody.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MESSAGE_BODY_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, MESSAGE_BODY_LIMIT) + "...";
    }
}
