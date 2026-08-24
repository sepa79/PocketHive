package io.pockethive.mcp.application;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class EnvironmentHealthService {
    private final URI publicIngress;
    private final List<EnvironmentHealthTarget> targets;
    private final EnvironmentHealthProbePort probes;
    private final Clock clock;

    public EnvironmentHealthService(URI publicIngress,
                                    List<EnvironmentHealthTarget> targets,
                                    EnvironmentHealthProbePort probes,
                                    Clock clock) {
        this.publicIngress = publicIngress;
        this.targets = List.copyOf(targets);
        this.probes = probes;
        this.clock = clock;
    }

    public EnvironmentHealthView read() {
        Instant observedAt = clock.instant();
        List<EnvironmentServiceHealth> services = probeAll(observedAt);
        long healthyCount = services.stream()
            .filter(service -> service.status() == EnvironmentServiceStatus.HEALTHY)
            .count();
        EnvironmentHealthStatus status = healthyCount == services.size()
            ? EnvironmentHealthStatus.HEALTHY
            : healthyCount == 0 ? EnvironmentHealthStatus.UNAVAILABLE : EnvironmentHealthStatus.DEGRADED;
        return new EnvironmentHealthView(status, services, observedAt);
    }

    private List<EnvironmentServiceHealth> probeAll(Instant observedAt) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EnvironmentServiceHealth>> futures = targets.stream()
                .map(target -> executor.submit(() -> serviceHealth(target, observedAt)))
                .toList();
            List<EnvironmentServiceHealth> result = new ArrayList<>(futures.size());
            for (Future<EnvironmentServiceHealth> future : futures) {
                result.add(future.get());
            }
            return List.copyOf(result);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return unavailable(observedAt);
        } catch (java.util.concurrent.ExecutionException exception) {
            return unavailable(observedAt);
        }
    }

    private EnvironmentServiceHealth serviceHealth(EnvironmentHealthTarget target, Instant observedAt) {
        EnvironmentServiceStatus status;
        try {
            status = probes.healthy(target)
                ? EnvironmentServiceStatus.HEALTHY
                : EnvironmentServiceStatus.UNAVAILABLE;
        } catch (RuntimeException exception) {
            status = EnvironmentServiceStatus.UNAVAILABLE;
        }
        return new EnvironmentServiceHealth(target.id(), target.name(),
            publicIngress.resolve(target.endpointPath()), status, observedAt);
    }

    private List<EnvironmentServiceHealth> unavailable(Instant observedAt) {
        return targets.stream()
            .map(target -> new EnvironmentServiceHealth(target.id(), target.name(),
                publicIngress.resolve(target.endpointPath()), EnvironmentServiceStatus.UNAVAILABLE, observedAt))
            .toList();
    }
}
