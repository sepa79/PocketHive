package io.pockethive.manager.runtime;

/**
 * Responsibility: carry normalized mount diagnostics and field availability.
 * Must not: decode Docker models or choose source redaction.
 * Contract: RESP-DOCKER-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-docker-runtime.
 */
public record RuntimeMountInspection(String type, String name, String source, String destination,
                                     String mode, Object writable, String propagation, boolean reportsPropagation) {
}
