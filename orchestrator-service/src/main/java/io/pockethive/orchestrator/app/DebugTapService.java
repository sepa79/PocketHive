package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.app.DebugTapController.DebugTapRequest;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapResponse;
import io.pockethive.orchestrator.app.DebugTapController.DebugTapSample;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmRegistry;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.Work;
import java.nio.charset.StandardCharsets;
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
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Binding.DestinationType;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DebugTapService {

    private static final int DEFAULT_MAX_ITEMS = 1;
    private static final int DEFAULT_TTL_SECONDS = 60;

    private final SwarmRegistry swarmRegistry;
    private final AmqpAdmin amqp;
    private final RabbitTemplate rabbitTemplate;
    private final ConcurrentMap<String, DebugTap> taps = new ConcurrentHashMap<>();

    public DebugTapService(SwarmRegistry swarmRegistry, AmqpAdmin amqp, RabbitTemplate rabbitTemplate) {
        this.swarmRegistry = Objects.requireNonNull(swarmRegistry, "swarmRegistry");
        this.amqp = Objects.requireNonNull(amqp, "amqp");
        this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    }

    public DebugTapResponse create(DebugTapRequest request) {
        Objects.requireNonNull(request, "request");
        TapDirection direction = TapDirection.from(request.direction());
        TapBinding binding = resolveBinding(request, direction);
        int maxItems = resolveMaxItems(request.maxItems());
        int ttlSeconds = resolveTtlSeconds(request.ttlSeconds());

        String tapId = UUID.randomUUID().toString();
        String tapQueue = "ph.debug.%s.%s.%s".formatted(binding.swarmId(), binding.role(), shortId(tapId));

        Map<String, Object> args = Map.of(
            "x-message-ttl", ttlSeconds * 1000L,
            "x-max-length", maxItems
        );
        Queue queue = QueueBuilder.nonDurable(tapQueue)
            .exclusive()
            .autoDelete()
            .withArguments(args)
            .build();
        amqp.declareQueue(queue);
        Binding bindingDef = new Binding(queue.getName(), DestinationType.QUEUE, binding.exchange(), binding.routingKey(), null);
        amqp.declareBinding(bindingDef);

        DebugTap tap = new DebugTap(
            tapId,
            binding.swarmId(),
            binding.role(),
            direction,
            binding.ioName(),
            binding.exchange(),
            binding.routingKey(),
            queue.getName(),
            maxItems,
            ttlSeconds,
            Instant.now()
        );
        taps.put(tapId, tap);
        return tap.snapshot();
    }

    public DebugTapResponse read(String tapId, Integer drain) {
        DebugTap tap = requireTap(tapId);
        int limit = resolveDrainLimit(drain, tap.maxItems());
        tap.drain(rabbitTemplate, limit);
        return tap.snapshot();
    }

    public DebugTapResponse close(String tapId) {
        DebugTap tap = taps.remove(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        amqp.deleteQueue(tap.queue());
        return tap.snapshot();
    }

    private DebugTap requireTap(String tapId) {
        DebugTap tap = taps.get(tapId);
        if (tap == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "debug tap not found");
        }
        return tap;
    }

    private TapBinding resolveBinding(DebugTapRequest request, TapDirection direction) {
        String swarmId = normalize(request.swarmId(), "swarmId");
        String role = normalize(request.role(), "role");
        Swarm swarm = swarmRegistry.find(swarmId)
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
        String exchange = "ph." + swarmId + ".hive";
        String routingKey = "ph.work." + swarmId + "." + suffix.trim();
        return new TapBinding(swarmId, role, ioName, exchange, routingKey);
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
        return Math.max(1, requested);
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

    private static String shortId(String tapId) {
        return tapId.substring(0, 8);
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
            this.lastReadAt = createdAt;
        }

        String queue() {
            return queue;
        }

        int maxItems() {
            return maxItems;
        }

        void drain(RabbitTemplate template, int limit) {
            int drained = 0;
            while (drained < limit) {
                Message message = template.receive(queue);
                if (message == null) {
                    break;
                }
                byte[] body = message.getBody() == null ? new byte[0] : message.getBody();
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
