package io.pockethive.worker.sdk.capabilities;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable view of a worker capabilities manifest loaded from the classpath.
 */
public final class WorkerCapabilitiesManifest {

    private final String schemaVersion;
    private final String capabilitiesVersion;
    private final String role;
    private final Map<String, Object> payload;

    public WorkerCapabilitiesManifest(
        String schemaVersion,
        String capabilitiesVersion,
        String role,
        Map<String, Object> payload
    ) {
        this.schemaVersion = requireText(schemaVersion, "schemaVersion");
        this.capabilitiesVersion = requireText(capabilitiesVersion, "capabilitiesVersion");
        this.role = requireText(role, "role");
        this.payload = payload == null || payload.isEmpty() ? Map.of() : Map.copyOf(payload);
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String capabilitiesVersion() {
        return capabilitiesVersion;
    }

    public String role() {
        return role;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        WorkerCapabilitiesManifest that = (WorkerCapabilitiesManifest) o;
        return schemaVersion.equals(that.schemaVersion)
            && capabilitiesVersion.equals(that.capabilitiesVersion)
            && role.equals(that.role)
            && payload.equals(that.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, capabilitiesVersion, role, payload);
    }

    @Override
    public String toString() {
        return "WorkerCapabilitiesManifest{"
            + "schemaVersion='" + schemaVersion + '\''
            + ", capabilitiesVersion='" + capabilitiesVersion + '\''
            + ", role='" + role + '\''
            + '}';
    }
}
