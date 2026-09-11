package io.pockethive.orchestrator.runtime;
import java.util.Optional;
/**
 * Responsibility: carry validated cleanup selection from the request boundary.
 * Must not: select resources or infer resource ownership.
 * Contract: docs/ORCHESTRATOR-REST.md.
 */
record CleanupScope(String computeAdapter, String swarmId, Optional<String> runId,
                    boolean includeRunning, boolean includeRabbit) { }
