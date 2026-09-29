package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapRequest;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapResponse;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.topology.work.WorkTopologyResolver;
import io.pockethive.topology.work.WorkChannelAddress;
import io.pockethive.topology.work.WorkDebugTap;
import io.pockethive.topology.work.WorkDebugTaps;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: manage temporary Work debug taps and their captured samples.
 * Must not: reconstruct source addresses, map Rabbit queue arguments, mutate swarm topology/lifecycle,
 * or report a failed explicit close as successful.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names;
 * source destinations and capture operations come from the explicitly selected Work owner.
 */
@Service
public class DebugTapService {

    private static final int DEFAULT_MAX_ITEMS = 1;
    private static final int DEFAULT_TTL_SECONDS = 60;

    private final SwarmStore swarmStore;
    private final WorkDebugTaps debugTaps;
    private final WorkTopologyResolver topologyResolver;
    private final ConcurrentMap<String, DebugTapSession> taps = new ConcurrentHashMap<>();

    public DebugTapService(SwarmStore swarmStore, WorkDebugTaps debugTaps, WorkTopologyResolver topologyResolver) {
        this.swarmStore = Objects.requireNonNull(swarmStore, "swarmStore");
        this.debugTaps = Objects.requireNonNull(debugTaps, "debugTaps");
        this.topologyResolver = Objects.requireNonNull(topologyResolver, "topologyResolver");
    }

    public DebugTapResponse create(DebugTapRequest request) {
        Objects.requireNonNull(request, "request");
        cleanupExpired(Instant.now());
        DebugTapDirection direction = DebugTapDirection.from(request.direction());
        TapBinding binding = resolveBinding(request, direction);
        int maxItems = resolveMaxItems(request.maxItems());
        int ttlSeconds = resolveTtlSeconds(request.ttlSeconds());

        String tapId = UUID.randomUUID().toString();
        WorkDebugTap capture;
        try {
            capture = debugTaps.open(binding.swarmId(), binding.role(), tapId, binding.source(), ttlSeconds, maxItems);
        } catch (UnsupportedOperationException unsupported) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, unsupported.getMessage(), unsupported);
        }
        DebugTapSession tap = new DebugTapSession(tapId, binding.swarmId(), binding.role(), direction,
            binding.ioName(), capture, maxItems, ttlSeconds, Instant.now());
        taps.put(tapId, tap);
        return tap.snapshot();
    }

    public DebugTapResponse read(String tapId, Integer drain) {
        cleanupExpired(Instant.now());
        DebugTapSession tap = requireTap(tapId);
        int limit = resolveDrainLimit(drain, tap.maxItems());
        tap.drain(limit);
        return tap.snapshot();
    }

    public DebugTapResponse describe(String tapId) {
        cleanupExpired(Instant.now());
        return requireTap(tapId).snapshot();
    }

    public DebugTapResponse close(String tapId) {
        DebugTapSession tap = taps.remove(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        try {
            tap.transport().close();
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Cannot close debug tap " + tapId, failure);
        }
        return tap.snapshot();
    }

    private DebugTapSession requireTap(String tapId) {
        DebugTapSession tap = taps.get(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        Instant now = Instant.now();
        if (tap.isExpired(now)) {
            taps.remove(tapId, tap);
            safeClose(tap);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap expired");
        }
        return tap;
    }

    @Scheduled(fixedDelay = 5000L)
    void cleanupExpiredScheduled() {
        cleanupExpired(Instant.now());
    }

    void cleanupExpired(Instant now) {
        for (Map.Entry<String, DebugTapSession> entry : taps.entrySet()) {
            String tapId = entry.getKey();
            DebugTapSession tap = entry.getValue();
            if (!tap.isExpired(now)) {
                continue;
            }
            if (taps.remove(tapId, tap)) {
                safeClose(tap);
            }
        }
    }

    private void safeClose(DebugTapSession tap) {
        try {
            tap.transport().close();
        } catch (Exception ignored) {
            // Preserve best-effort cleanup of temporary captures.
        }
    }

    private TapBinding resolveBinding(DebugTapRequest request, DebugTapDirection direction) {
        String swarmId = normalize(request.swarmId(), "swarmId");
        String role = normalize(request.role(), "role");
        Swarm swarm = swarmStore.find(swarmId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown swarmId"));
        Bee bee = findBee(swarm, role);
        Work work = bee.work();
        if (work == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no work configuration for role");
        }
        Map<String, String> ports = direction == DebugTapDirection.IN ? work.in() : work.out();
        if (ports == null || ports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no work ports for role");
        }
        String ioName = normalizeOptional(request.ioName());
        if (ioName == null) {
            ioName = direction == DebugTapDirection.IN ? "in" : "out";
        }
        String suffix = ports.get(ioName);
        if (suffix == null || suffix.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown ioName for role");
        }
        var topology = topologyResolver.resolve(swarmId, java.util.Set.of(suffix));
        return new TapBinding(swarmId, role, ioName, topology.channel(suffix));
    }

    private Bee findBee(Swarm swarm, String role) {
        return swarm.bees().stream()
            .filter(bee -> role.equalsIgnoreCase(bee.role()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown role for swarm"));
    }

    private int resolveMaxItems(Integer requested) {
        if (requested == null) {
            return DEFAULT_MAX_ITEMS;
        }
        return Math.max(1, requested);
    }

    private int resolveTtlSeconds(Integer requested) {
        if (requested == null) {
            return DEFAULT_TTL_SECONDS;
        }
        return Math.max(1, requested);
    }

    private int resolveDrainLimit(Integer requested, int maxItems) {
        if (requested == null) {
            return maxItems;
        }
        if (requested <= 0) {
            return 0;
        }
        return Math.min(requested, maxItems);
    }

    private static String normalize(String value, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return trimmed;
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record TapBinding(String swarmId, String role, String ioName, WorkChannelAddress source) {
    }
}
