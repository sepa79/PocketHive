package io.pockethive.mcp.application;

/** Canonical public HTTP contract for binary Scenario Bundle uploads. */
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
