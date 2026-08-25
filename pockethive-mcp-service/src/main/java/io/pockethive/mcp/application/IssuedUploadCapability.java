package io.pockethive.mcp.application;

import java.util.Objects;

public record IssuedUploadCapability(String value, String digest) {
    public IssuedUploadCapability {
        Objects.requireNonNull(value);
        UploadCapabilityAuthority.requireDigest(digest);
    }
}
