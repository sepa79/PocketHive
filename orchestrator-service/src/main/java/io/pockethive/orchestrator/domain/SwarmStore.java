package io.pockethive.orchestrator.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
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
        if (swarm == null) {
            return null;
        }
        swarms.put(swarm.getId(), swarm);
        log.info("SwarmStore: registered swarm id={} instance={} container={}",
            swarm.getId(), swarm.getInstanceId(), swarm.getContainerId());
        return swarm;
    }

    public Optional<Swarm> find(String id) {
        return Optional.ofNullable(swarms.get(id));
    }

    public boolean ensureDiscoveredSwarm(String swarmId, String controllerInstance, String runId) {
        if (swarmId == null || swarmId.isBlank()) {
            return false;
        }
        if (find(swarmId).isPresent()) {
            return false;
        }
        if (controllerInstance == null || controllerInstance.isBlank()) {
            return false;
        }
        Swarm swarm = new Swarm(swarmId, controllerInstance, controllerInstance, runId);
        register(swarm);
        updateStatus(swarmId, SwarmLifecycleStatus.CREATING);
        updateStatus(swarmId, SwarmLifecycleStatus.READY);
        return true;
    }

    public Collection<Swarm> all() {
        return Collections.unmodifiableCollection(swarms.values());
    }

    public void remove(String id) {
        Swarm removed = swarms.remove(id);
        if (removed != null) {
            log.info("SwarmStore: removed swarm id={} instance={} container={}",
                removed.getId(), removed.getInstanceId(), removed.getContainerId());
        } else {
            log.info("SwarmStore: remove called for unknown swarm id={}", id);
        }
    }

    public void updateStatus(String id, SwarmLifecycleStatus status) {
        Swarm swarm = swarms.get(id);
        if (swarm != null) {
            SwarmLifecycleStatus previous = swarm.getStatus();
            swarm.transitionTo(status);
            if (previous != status) {
                log.info("SwarmStore: status change id={} {} -> {}", id, previous, status);
            }
        }
    }

    public void markTemplateApplied(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        if (swarm.getStatus() == SwarmLifecycleStatus.CREATING) {
            swarm.transitionTo(SwarmLifecycleStatus.READY);
        }
    }

    public void markStartIssued(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmLifecycleStatus status = swarm.getStatus();
        if (status == SwarmLifecycleStatus.STARTING || status == SwarmLifecycleStatus.RUNNING) {
            return;
        }
        if (status == SwarmLifecycleStatus.READY || status == SwarmLifecycleStatus.STOPPED) {
            swarm.transitionTo(SwarmLifecycleStatus.STARTING);
        }
    }

    public void markStartConfirmed(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmLifecycleStatus status = swarm.getStatus();
        if (status == SwarmLifecycleStatus.RUNNING) {
            return;
        }
        if (status == SwarmLifecycleStatus.READY || status == SwarmLifecycleStatus.STOPPED) {
            swarm.transitionTo(SwarmLifecycleStatus.STARTING);
        }
        if (swarm.getStatus() == SwarmLifecycleStatus.STARTING) {
            swarm.transitionTo(SwarmLifecycleStatus.RUNNING);
        }
    }

    /**
     * Remove swarms whose swarm-controller stopped reporting status metrics.
     * <p>
     * This is intentionally strict: only swarms that have a recorded controller status timestamp and
     * have not been seen for at least {@code failedAfter} are pruned.
     */
    public void pruneStaleControllers(Duration failedAfter) {
        pruneStaleControllers(failedAfter, Instant.now());
    }

    void pruneStaleControllers(Duration failedAfter, Instant now) {
        if (failedAfter == null || failedAfter.isNegative() || failedAfter.isZero()) {
            return;
        }
        if (now == null) {
            return;
        }
        swarms.values().removeIf(s -> {
            Instant lastSeenAt = s.getControllerStatusReceivedAt();
            if (lastSeenAt == null) {
                return false;
            }
            if (!now.isAfter(lastSeenAt.plus(failedAfter))) {
                return false;
            }
            log.info("SwarmStore: pruning stale swarm id={} instance={} container={} lastSeenAt={}",
                s.getId(), s.getInstanceId(), s.getContainerId(), lastSeenAt);
            return true;
        });
    }

    public int count() {
        return swarms.size();
    }

    public void cacheControllerStatusFull(String swarmId, JsonNode envelope, Instant receivedAt) {
        if (swarmId == null || swarmId.isBlank()) {
            return;
        }
        Swarm swarm = swarms.get(swarmId);
        if (swarm == null) {
            return;
        }
        swarm.updateControllerStatusFull(envelope, receivedAt);
    }

    public DeltaApplyResult applyControllerStatusDelta(String swarmId, JsonNode deltaEnvelope, Instant receivedAt) {
        if (swarmId == null || swarmId.isBlank()) {
            return DeltaApplyResult.SWARM_NOT_FOUND;
        }
        Swarm swarm = swarms.get(swarmId);
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
        return data.has("config") || data.has("io") || data.has("startedAt") || data.has("runtime");
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
}
