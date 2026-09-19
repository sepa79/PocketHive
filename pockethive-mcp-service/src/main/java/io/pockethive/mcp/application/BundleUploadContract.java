package io.pockethive.mcp.application;

/**
 * Responsibility: Define the canonical public HTTP path and capability header for bundle uploads.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class BundleUploadContract {
    public static final String PATH_PREFIX = "/mcp/uploads/";
    public static final String PATH_PATTERN = PATH_PREFIX + "{ticketId}";
    public static final String SECURITY_PATTERN = PATH_PREFIX + "**";
    public static final String UPLOAD_CAPABILITY_HEADER = "PocketHive-Upload-Capability";
    public static final String UPLOAD_CAPABILITY_CHALLENGE =
        "PocketHiveUploadCapability header=\"" + UPLOAD_CAPABILITY_HEADER + "\"";

    private BundleUploadContract() {
    }
}
