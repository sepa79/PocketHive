package io.pockethive.mcp.application;

public sealed interface UploadOutcome permits ValidationUploadOutcome, PublicationUploadOutcome {
}
