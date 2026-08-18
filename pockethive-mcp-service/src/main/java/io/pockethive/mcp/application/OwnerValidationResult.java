package io.pockethive.mcp.application;

public record OwnerValidationResult(boolean valid, String scenarioId, String bundleContentDigest,
                                    Object ownerResult) {
}
