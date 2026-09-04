package io.pockethive.capabilities.api;

/**
 * Responsibility: Project one scenario worker role and image in the capability HTTP response.
 * Must not: Resolve images or worker capabilities.
 * Contract: docs/architecture/workerCapabilities.md.
 */
public record BeeImage(String role, String image) {
}
