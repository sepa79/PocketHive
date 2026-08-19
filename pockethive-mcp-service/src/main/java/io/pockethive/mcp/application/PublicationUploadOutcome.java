package io.pockethive.mcp.application;

public record PublicationUploadOutcome(PublicationAttemptView publicationAttempt) implements UploadOutcome {
}
