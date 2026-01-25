package io.pockethive.orchestrator.domain;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwarmRegistry {

    private static final Logger log = LoggerFactory.getLogger(SwarmRegistry.class);

    private final Map<String, Swarm> swarms = new ConcurrentHashMap<>();

    public void clear() {
        swarms.clear();
        log.info("SwarmRegistry: cleared");
    }

    public Swarm register(Swarm swarm) {
        if (swarm == null) {
            return null;
        }
        swarms.put(swarm.getId(), swarm);
        log.info("SwarmRegistry: registered swarm id={} instance={} container={}",
            swarm.getId(), swarm.getInstanceId(), swarm.getContainerId());
        return swarm;
    }

    public Optional<Swarm> find(String id) {
        return Optional.ofNullable(swarms.get(id));
    }

    public Collection<Swarm> all() {
        return Collections.unmodifiableCollection(swarms.values());
    }

    public void remove(String id) {
        Swarm removed = swarms.remove(id);
        if (removed != null) {
            log.info("SwarmRegistry: removed swarm id={} instance={} container={}",
                removed.getId(), removed.getInstanceId(), removed.getContainerId());
        } else {
            log.info("SwarmRegistry: remove called for unknown swarm id={}", id);
        }
    }

    public void updateStatus(String id, SwarmStatus status) {
        Swarm swarm = swarms.get(id);
        if (swarm != null) {
            SwarmStatus previous = swarm.getStatus();
            swarm.transitionTo(status);
            if (previous != status) {
                log.info("SwarmRegistry: status change id={} {} -> {}", id, previous, status);
            }
        }
    }

    public void markTemplateApplied(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        if (swarm.getStatus() == SwarmStatus.CREATING) {
            swarm.transitionTo(SwarmStatus.READY);
        }
    }

    public void markStartIssued(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmStatus status = swarm.getStatus();
        if (status == SwarmStatus.STARTING || status == SwarmStatus.RUNNING) {
            return;
        }
        if (status == SwarmStatus.READY || status == SwarmStatus.STOPPED) {
            swarm.transitionTo(SwarmStatus.STARTING);
        }
    }

    public void markStartConfirmed(String id) {
        Swarm swarm = swarms.get(id);
        if (swarm == null) {
            return;
        }
        SwarmStatus status = swarm.getStatus();
        if (status == SwarmStatus.RUNNING) {
            return;
        }
        if (status == SwarmStatus.READY || status == SwarmStatus.STOPPED) {
            swarm.transitionTo(SwarmStatus.STARTING);
        }
        if (swarm.getStatus() == SwarmStatus.STARTING) {
            swarm.transitionTo(SwarmStatus.RUNNING);
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
            log.info("SwarmRegistry: pruning stale swarm id={} instance={} container={} lastSeenAt={}",
                s.getId(), s.getInstanceId(), s.getContainerId(), lastSeenAt);
            return true;
        });
    }

    public int count() {
        return swarms.size();
    }
}
