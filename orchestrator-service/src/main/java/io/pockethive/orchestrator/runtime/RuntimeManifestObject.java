package io.pockethive.orchestrator.runtime;

/**
 * Responsibility: carry the immutable compute object projection of the runtime ownership manifest.
 * Must not: resolve names, access resources or decide lifecycle/cleanup outcomes.
 * Contract: RESP-RUNTIME-CLEANUP — docs/architecture/runtime-responsibilities.md#resp-runtime-cleanup.
 */
public record RuntimeManifestObject(String runtimeId, String runtimeType, String resourceKind,
                                    String role, String instance, String image) { }
