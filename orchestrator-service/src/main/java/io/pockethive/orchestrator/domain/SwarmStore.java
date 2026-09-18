package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwarmStore {

    private static final Logger log = LoggerFactory.getLogger(SwarmStore.class);

    private final Map<String, Swarm> swarms = new ConcurrentHashMap<>();

    public enum DeltaApplyResult {
        MERGED,
        MISSING_BASELINE,
        REJECTED_FULL_ONLY_FIELDS,
        NOT_OBJECT,
        SWARM_NOT_FOUND
    }

    public void clear() {
        swarms.clear();
        log.info("SwarmStore: cleared");
    }

    public Swarm register(Swarm swarm) {
        Objects.requireNonNull(swarm, "swarm");
        String swarmId = requireSwarmId(swarm.getId());
        swarms.put(swarmId, swarm);
        log.info("SwarmStore: registered swarm id={} instance={} container={}",
            swarm.getId(), swarm.getInstanceId(), swarm.getContainerId());
        return swarm;
    }

    public Optional<Swarm> find(String id) {
        return Optional.ofNullable(swarms.get(requireSwarmId(id)));
    }

    public Collection<Swarm> all() {
        return Collections.unmodifiableCollection(swarms.values());
    }

    public void remove(String id) {
        String swarmId = requireSwarmId(id);
        Swarm removed = swarms.remove(swarmId);
        if (removed != null) {
            log.info("SwarmStore: removed swarm id={} instance={} container={}",
                removed.getId(), removed.getInstanceId(), removed.getContainerId());
        } else {
            log.info("SwarmStore: remove called for unknown swarm id={}", swarmId);
        }
    }

    /** Marks stale observation unknown; status traffic never creates or deletes registry entries. */
    public void pruneStaleControllers(Duration failedAfter) {
        pruneStaleControllers(failedAfter, Instant.now());
    }

    void pruneStaleControllers(Duration failedAfter, Instant now) {
        Objects.requireNonNull(failedAfter, "failedAfter");
        Objects.requireNonNull(now, "now");
        if (failedAfter.isNegative() || failedAfter.isZero()) {
            throw new IllegalArgumentException("failedAfter must be positive");
        }
        swarms.values().forEach(s -> {
            Instant lastSeenAt = s.getControllerStatusReceivedAt();
            if (lastSeenAt == null) {
                return;
            }
            if (!now.isAfter(lastSeenAt.plus(failedAfter))) {
                return;
            }
            log.info("SwarmStore: marking stale observation id={} instance={} container={} lastSeenAt={}",
                s.getId(), s.getInstanceId(), s.getContainerId(), lastSeenAt);
            s.markObservationStale();
        });
    }

    public int count() {
        return swarms.size();
    }

    public void cacheControllerStatusFull(String swarmId, JsonNode envelope, Instant receivedAt) {
        String canonicalSwarmId = requireSwarmId(swarmId);
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Swarm swarm = swarms.get(canonicalSwarmId);
        if (swarm == null) {
            return;
        }
        swarm.updateControllerStatusFull(envelope, receivedAt);
    }

    public DeltaApplyResult applyControllerStatusDelta(String swarmId, JsonNode deltaEnvelope, Instant receivedAt) {
        String canonicalSwarmId = requireSwarmId(swarmId);
        Objects.requireNonNull(deltaEnvelope, "deltaEnvelope");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Swarm swarm = swarms.get(canonicalSwarmId);
        if (swarm == null) {
            return DeltaApplyResult.SWARM_NOT_FOUND;
        }
        JsonNode current = swarm.getControllerStatusFull();
        if (current == null) {
            return DeltaApplyResult.MISSING_BASELINE;
        }
        if (!(current instanceof ObjectNode currentObject) || !(deltaEnvelope instanceof ObjectNode deltaObject)) {
            return DeltaApplyResult.NOT_OBJECT;
        }
        if (deltaContainsFullOnlyFields(deltaObject.path("data"))) {
            return DeltaApplyResult.REJECTED_FULL_ONLY_FIELDS;
        }
        ObjectNode merged = currentObject.deepCopy();
        mergeStatusDeltaPayload(merged, deltaObject);
        swarm.updateControllerStatusFull(merged, receivedAt);
        return DeltaApplyResult.MERGED;
    }

    private boolean deltaContainsFullOnlyFields(JsonNode data) {
        if (data == null || data.isMissingNode()) {
            return false;
        }
        return data.has("config") || data.has("io") || data.has("startedAt");
    }

    private void mergeStatusDeltaPayload(ObjectNode target, ObjectNode delta) {
        JsonNode timestamp = delta.get("timestamp");
        if (timestamp != null && timestamp.isTextual()) {
            target.put("timestamp", timestamp.asText());
        }
        JsonNode deltaData = delta.get("data");
        if (deltaData instanceof ObjectNode deltaObject) {
            ObjectNode targetData = target.path("data") instanceof ObjectNode existing
                ? existing
                : target.putObject("data");
            mergeObjectNodes(targetData, deltaObject);
        }
    }

    private void mergeObjectNodes(ObjectNode target, ObjectNode source) {
        Iterator<String> fields = source.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            JsonNode sourceValue = source.get(field);
            JsonNode targetValue = target.get(field);
            if (sourceValue instanceof ObjectNode sourceObject
                && targetValue instanceof ObjectNode targetObject) {
                mergeObjectNodes(targetObject, sourceObject);
            } else {
                if (sourceValue == null) {
                    target.putNull(field);
                } else {
                    target.set(field, sourceValue.deepCopy());
                }
            }
        }
    }

    private static String requireSwarmId(String swarmId) {
        Objects.requireNonNull(swarmId, "swarmId");
        if (swarmId.isBlank() || !swarmId.equals(swarmId.trim())) {
            throw new IllegalArgumentException("swarmId must be non-blank and normalized");
        }
        return swarmId;
    }
}
