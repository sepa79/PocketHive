package io.pockethive.orchestrator.app;

import io.pockethive.rabbit.api.RabbitResourceNames;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapRequest;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapResponse;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapSample;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import io.pockethive.topology.work.WorkResourceNamesPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.rabbit.api.RabbitDebugTapSpec;
import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitReceiver;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: manage temporary Work debug taps and their captured samples.
 * Must not: reconstruct source addresses, map Rabbit queue arguments or mutate swarm topology and lifecycle state.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names;
 * source destinations come from the injected naming port; temporary tap lifecycle remains local and broker operations use RabbitResources.
 */
@Service
public class DebugTapService {

    private static final int DEFAULT_MAX_ITEMS = 1;
    private static final int DEFAULT_TTL_SECONDS = 60;

    private final SwarmStore swarmStore;
    private final RabbitResources amqp;
    private final RabbitReceiver rabbitTemplate;
    private final WorkResourceNamesPort workNames;
    private final ConcurrentMap<String, DebugTap> taps = new ConcurrentHashMap<>();

    public DebugTapService(SwarmStore swarmStore, @org.springframework.beans.factory.annotation.Qualifier(io.pockethive.rabbit.api.RabbitResourceBeans.WORK) RabbitResources amqp, RabbitReceiver rabbitTemplate,
                           WorkResourceNamesPort workNames) {
        this.swarmStore = Objects.requireNonNull(swarmStore, "swarmStore");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.workNames = Objects.requireNonNull(workNames, "workNames");
    }

    public DebugTapResponse create(DebugTapRequest request) {
        Objects.requireNonNull(request, "request");
        cleanupExpired(Instant.now());
        TapDirection direction = TapDirection.from(request.direction());
        TapBinding binding = resolveBinding(request, direction);
        int maxItems = resolveMaxItems(request.maxItems());
        int ttlSeconds = resolveTtlSeconds(request.ttlSeconds());

        String tapId = UUID.randomUUID().toString();
        String tapQueue = RabbitResourceNames.debugTapQueue(binding.swarmId(), binding.role(), tapId);

        var specification = RabbitDebugTapSpec.create(tapQueue, binding.exchange(), binding.routingKey(), ttlSeconds, maxItems);
        amqp.declareQueue(specification.queue());
        amqp.bind(specification.binding());

        DebugTap tap = new DebugTap(
            tapId,
            binding.swarmId(),
            binding.role(),
            direction,
            binding.ioName(),
            binding.exchange(),
            binding.routingKey(),
            tapQueue,
            maxItems,
            ttlSeconds,
            Instant.now()
        );
        taps.put(tapId, tap);
        return tap.snapshot();
    }

    public DebugTapResponse read(String tapId, Integer drain) {
        cleanupExpired(Instant.now());
        DebugTap tap = requireTap(tapId);
        int limit = resolveDrainLimit(drain, tap.maxItems());
        tap.drain(rabbitTemplate, limit);
        return tap.snapshot();
    }

    public DebugTapResponse describe(String tapId) {
        cleanupExpired(Instant.now());
        return requireTap(tapId).snapshot();
    }

    public DebugTapResponse close(String tapId) {
        DebugTap tap = taps.remove(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        safeDeleteQueue(tap.queue());
        return tap.snapshot();
    }

    private DebugTap requireTap(String tapId) {
        DebugTap tap = taps.get(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        Instant now = Instant.now();
        if (tap.isExpired(now)) {
            taps.remove(tapId, tap);
            safeDeleteQueue(tap.queue());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap expired");
        }
        return tap;
    }

    @Scheduled(fixedDelay = 5000L)
    void cleanupExpiredScheduled() {
        cleanupExpired(Instant.now());
    }

    void cleanupExpired(Instant now) {
        for (Map.Entry<String, DebugTap> entry : taps.entrySet()) {
            String tapId = entry.getKey();
            DebugTap tap = entry.getValue();
            if (!tap.isExpired(now)) {
                continue;
            }
            if (taps.remove(tapId, tap)) {
                safeDeleteQueue(tap.queue());
            }
        }
    }

    private void safeDeleteQueue(String name) {
        try {
            amqp.deleteQueue(name);
        } catch (Exception ignored) {
            // best-effort cleanup: tap queues are exclusive + auto-delete
        }
    }

    private TapBinding resolveBinding(DebugTapRequest request, TapDirection direction) {
        String swarmId = normalize(request.swarmId(), "swarmId");
        String role = normalize(request.role(), "role");
        Swarm swarm = swarmStore.find(swarmId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown swarmId"));
        Bee bee = findBee(swarm, role);
        Work work = bee.work();
        if (work == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no work configuration for role");
        }
        Map<String, String> ports = direction == TapDirection.IN ? work.in() : work.out();
        if (ports == null || ports.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no work ports for role");
        }
        String ioName = normalizeOptional(request.ioName());
        if (ioName == null) {
            ioName = direction == TapDirection.IN ? "in" : "out";
        }
        String suffix = ports.get(ioName);
        if (suffix == null || suffix.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown ioName for role");
        }
        var topology = workNames.forSwarm(swarmId);
        var address = workNames.address(topology.hiveExchange(), topology.queuePrefix(), suffix);
        return new TapBinding(swarmId, role, ioName, address.exchange(), address.routingKey());
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

    private enum TapDirection {
        IN,
        OUT;

        static TapDirection from(String raw) {
            if (raw == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction is required");
            }
            String value = raw.trim().toUpperCase(Locale.ROOT);
            for (TapDirection direction : values()) {
                if (direction.name().equals(value)) {
                    return direction;
                }
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "direction must be IN or OUT");
        }
    }

    private record TapBinding(String swarmId, String role, String ioName, String exchange, String routingKey) {
    }

    private static final class DebugTap {
        private final String id;
        private final String swarmId;
        private final String role;
        private final TapDirection direction;
        private final String ioName;
        private final String exchange;
        private final String routingKey;
        private final String queue;
        private final int maxItems;
        private final int ttlSeconds;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final Deque<DebugTapSample> samples = new ArrayDeque<>();
        private final Object lock = new Object();
        private volatile Instant lastReadAt;

        private DebugTap(String id,
                         String swarmId,
                         String role,
                         TapDirection direction,
                         String ioName,
                         String exchange,
                         String routingKey,
                         String queue,
                         int maxItems,
                         int ttlSeconds,
                         Instant createdAt) {
            this.id = id;
            this.swarmId = swarmId;
            this.role = role;
            this.direction = direction;
            this.ioName = ioName;
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.queue = queue;
            this.maxItems = maxItems;
            this.ttlSeconds = ttlSeconds;
            this.createdAt = createdAt;
            this.expiresAt = createdAt.plus(Duration.ofSeconds(ttlSeconds));
            this.lastReadAt = createdAt;
        }

        String queue() {
            return queue;
        }

        int maxItems() {
            return maxItems;
        }

        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }

        void drain(RabbitReceiver template, int limit) {
            int drained = 0;
            while (drained < limit) {
                RabbitMessage message = template.receive(queue).orElse(null);
                if (message == null) {
                    break;
                }
                byte[] body = message.body() == null ? new byte[0] : message.body();
                String payload = new String(body, StandardCharsets.UTF_8);
                DebugTapSample sample = new DebugTapSample(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    body.length,
                    payload
                );
                addSample(sample);
                drained++;
            }
            lastReadAt = Instant.now();
        }

        DebugTapResponse snapshot() {
            List<DebugTapSample> snapshot;
            synchronized (lock) {
                snapshot = List.copyOf(samples);
            }
            return new DebugTapResponse(
                id,
                swarmId,
                role,
                direction.name(),
                ioName,
                exchange,
                routingKey,
                queue,
                maxItems,
                ttlSeconds,
                createdAt,
                lastReadAt,
                snapshot
            );
        }

        private void addSample(DebugTapSample sample) {
            synchronized (lock) {
                while (samples.size() >= maxItems) {
                    samples.pollFirst();
                }
                samples.addLast(sample);
            }
        }
    }
}
