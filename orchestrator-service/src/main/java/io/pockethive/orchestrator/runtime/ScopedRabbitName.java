package io.pockethive.orchestrator.runtime;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import java.util.Objects;
/**
 * Responsibility: project one manifest-owned Rabbit name with its explicit plane.
 * Must not: infer resource ownership or reconstruct names.
 * Contract: docs/architecture/work-plane-boundaries.md.
 */
record ScopedRabbitName(ResourcePlane plane, String name) {
    ScopedRabbitName { Objects.requireNonNull(plane, "plane").requireRabbit(); Objects.requireNonNull(name, "name"); }
}
