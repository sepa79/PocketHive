package io.pockethive.mcp.application;

import java.util.Objects;

public record PreparedUpload<T extends BundleUploadTicket>(T ticket, String uploadCapability) {
    public PreparedUpload {
        Objects.requireNonNull(ticket);
        if (uploadCapability == null || uploadCapability.isBlank()) {
            throw new IllegalArgumentException("UPLOAD_CAPABILITY_REQUIRED");
        }
    }
}
