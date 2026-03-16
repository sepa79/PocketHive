package io.pockethive.orchestrator.app;

import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.observability.ControlPlaneJson;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.HiveJournal.HiveJournalEntry;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SwarmNetworkBindingService {
    private static final Logger log = LoggerFactory.getLogger(SwarmNetworkBindingService.class);

    private final NetworkProxyClient networkProxy;
    private final HiveJournal hiveJournal;
    private final ControlPlanePublisher controlPublisher;
    private final String originInstanceId;

    public SwarmNetworkBindingService(NetworkProxyClient networkProxy,
                                      HiveJournal hiveJournal,
                                      ControlPlanePublisher controlPublisher,
                                      ControlPlaneProperties controlPlaneProperties) {
        this.networkProxy = Objects.requireNonNull(networkProxy, "networkProxy");
        this.hiveJournal = Objects.requireNonNull(hiveJournal, "hiveJournal");
        this.controlPublisher = Objects.requireNonNull(controlPublisher, "controlPublisher");
        this.originInstanceId = requireOrigin(controlPlaneProperties);
    }

    public NetworkBinding applyBinding(String swarmId,
                                       String sutId,
                                       String networkProfileId,
                                       ResolvedSutEnvironment resolvedSutEnvironment,
                                       String correlationId,
                                       String idempotencyKey,
                                       String requestedBy,
                                       String reason,
                                       String journalOrigin) {
        try {
            NetworkBinding binding = networkProxy.bindSwarm(
                swarmId,
                new NetworkBindingRequest(
                    sutId,
                    NetworkMode.PROXIED,
                    networkProfileId,
                    requestedBy,
                    reason,
                    resolvedSutEnvironment),
                correlationId,
                idempotencyKey);
            appendNetworkBindingJournal(
                swarmId,
                "network-binding-apply",
                correlationId,
                idempotencyKey,
                binding.networkMode(),
                binding.networkProfileId(),
                requestedBy,
                binding.affectedEndpoints().size(),
                journalOrigin);
            return binding;
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to bind network proxy for swarm '%s'".formatted(swarmId), ex);
        }
    }

    public void rollbackBinding(String swarmId,
                                String sutId,
                                String correlationId,
                                String idempotencyKey,
                                String requestedBy,
                                String reason,
                                String journalOrigin,
                                RuntimeException createFailure) {
        try {
            clearBinding(swarmId, sutId, correlationId, idempotencyKey, requestedBy, reason, journalOrigin);
        } catch (Exception clearEx) {
            createFailure.addSuppressed(clearEx);
            log.warn("[CTRL] swarm-create rollback network binding FAILED swarm={} correlation={} idempotencyKey={}",
                swarmId, correlationId, idempotencyKey, clearEx);
        }
    }

    public NetworkBinding clearBinding(String swarmId,
                                       String sutId,
                                       String correlationId,
                                       String idempotencyKey,
                                       String requestedBy,
                                       String reason,
                                       String journalOrigin) {
        try {
            NetworkBinding cleared = networkProxy.clearSwarm(
                swarmId,
                new NetworkBindingClearRequest(sutId, requestedBy, reason),
                correlationId,
                idempotencyKey);
            appendNetworkBindingJournal(
                swarmId,
                "network-binding-clear",
                correlationId,
                idempotencyKey,
                cleared.effectiveMode(),
                null,
                requestedBy,
                0,
                journalOrigin);
            return cleared;
        } catch (Exception ex) {
            throw new IllegalStateException(
                "Failed to clear network proxy binding for swarm '%s'".formatted(swarmId), ex);
        }
    }

    public void publishControllerNetworkContext(Swarm swarm,
                                                String sutId,
                                                NetworkMode networkMode,
                                                String networkProfileId,
                                                String correlationId,
                                                String idempotencyKey) {
        if (swarm == null) {
            return;
        }
        String controllerInstance = normalize(swarm.getInstanceId());
        if (controllerInstance == null) {
            return;
        }
        ControlScope target = ControlScope.forInstance(swarm.getId(), "swarm-controller", controllerInstance);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sutId", normalize(sutId));
        data.put("networkMode", NetworkMode.directIfNull(networkMode).name());
        data.put("networkProfileId", normalize(networkProfileId));
        ControlSignal payload = ControlSignals.configUpdate(
            originInstanceId,
            target,
            correlationId,
            idempotencyKey,
            data);
        String routingKey = ControlPlaneRouting.signal(
            ControlPlaneSignals.CONFIG_UPDATE,
            swarm.getId(),
            "swarm-controller",
            controllerInstance);
        controlPublisher.publishSignal(new SignalMessage(routingKey, toJson(payload)));
    }

    public ResolvedSutEnvironment resolveSutEnvironment(SutEnvironment sutEnvironment, boolean proxied) {
        Map<String, ResolvedSutEndpoint> endpoints = new LinkedHashMap<>();
        for (Map.Entry<String, SutEndpoint> entry : sutEnvironment.endpoints().entrySet()) {
            SutEndpoint endpoint = Objects.requireNonNull(entry.getValue(), "sutEndpoint");
            String endpointKey = entry.getKey();
            ResolvedSutEndpoint resolvedEndpoint = resolveSutEndpoint(endpointKey, endpoint, proxied);
            endpoints.put(endpointKey, resolvedEndpoint);
        }
        return new ResolvedSutEnvironment(
            sutEnvironment.id(),
            sutEnvironment.name(),
            sutEnvironment.type(),
            endpoints);
    }

    private void appendNetworkBindingJournal(String swarmId,
                                             String action,
                                             String correlationId,
                                             String idempotencyKey,
                                             NetworkMode effectiveMode,
                                             String networkProfileId,
                                             String requestedBy,
                                             int affectedEndpointCount,
                                             String origin) {
        try {
            var data = new LinkedHashMap<String, Object>();
            data.put("effectiveMode", effectiveMode.name());
            data.put("networkProfileId", networkProfileId);
            data.put("requestedBy", normalize(requestedBy));
            data.put("affectedEndpointCount", affectedEndpointCount);
            hiveJournal.append(HiveJournalEntry.info(
                swarmId,
                HiveJournal.Direction.LOCAL,
                "network",
                action,
                normalize(origin) == null ? "orchestrator" : origin.trim(),
                ControlScope.forSwarm(swarmId),
                correlationId,
                idempotencyKey,
                null,
                data,
                null,
                null));
        } catch (Exception ignore) {
            // best-effort
        }
    }

    private static ResolvedSutEndpoint resolveSutEndpoint(String endpointKey, SutEndpoint endpoint, boolean proxied) {
        String endpointId = endpoint.id() == null || endpoint.id().isBlank() ? endpointKey : endpoint.id().trim();
        String kind = requireText(endpoint.kind(), "endpoint.kind");
        EndpointTarget clientTarget = parseEndpointTarget(endpointId, kind, endpoint.baseUrl(), "baseUrl");
        EndpointTarget upstreamTarget = proxied
            ? parseEndpointTarget(
                endpointId,
                kind,
                requireText(
                    endpoint.upstreamBaseUrl(),
                    "endpoint.upstreamBaseUrl must be provided when networkMode=PROXIED"),
                "upstreamBaseUrl")
            : clientTarget;
        URI uri = clientTarget.uri();
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                "SUT endpoint '%s' baseUrl must include host".formatted(endpointId));
        }
        int port = resolveAuthorityPort(endpointId, clientTarget.scheme(), uri.getPort());
        String clientAuthority = host + ":" + port;
        URI upstreamUri = upstreamTarget.uri();
        String upstreamHost = upstreamUri.getHost();
        if (upstreamHost == null || upstreamHost.isBlank()) {
            throw new IllegalStateException(
                "SUT endpoint '%s' upstreamBaseUrl must include host".formatted(endpointId));
        }
        int upstreamPort = resolveAuthorityPort(endpointId, upstreamTarget.scheme(), upstreamUri.getPort());
        String upstreamAuthority = upstreamHost + ":" + upstreamPort;
        return new ResolvedSutEndpoint(
            endpointId,
            kind,
            clientTarget.clientBaseUrl(),
            clientAuthority,
            upstreamAuthority);
    }

    private static EndpointTarget parseEndpointTarget(String endpointId,
                                                      String kind,
                                                      String baseUrl,
                                                      String fieldName) {
        String value = requireText(baseUrl, "endpoint." + fieldName);
        String normalizedKind = kind.trim().toLowerCase();
        if (!value.contains("://")) {
            if ("tcp".equals(normalizedKind) || "tcps".equals(normalizedKind)) {
                URI uri = parseUri(endpointId, normalizedKind + "://" + value);
                return new EndpointTarget(value, normalizedKind, uri);
            }
            throw new IllegalStateException(
                "SUT endpoint '%s' %s must include scheme".formatted(endpointId, fieldName));
        }
        URI uri = parseUri(endpointId, value);
        return new EndpointTarget(value, requireScheme(endpointId, uri), uri);
    }

    private static URI parseUri(String endpointId, String candidate) {
        try {
            return URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                "Invalid baseUrl for SUT endpoint '%s': %s".formatted(endpointId, candidate), ex);
        }
    }

    private static String requireScheme(String endpointId, URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalStateException(
                "SUT endpoint '%s' baseUrl must include scheme".formatted(endpointId));
        }
        return scheme.trim().toLowerCase();
    }

    private static int resolveAuthorityPort(String endpointId, String scheme, int configuredPort) {
        if (configuredPort > 0) {
            return configuredPort;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            case "tcp", "tcps" -> throw new IllegalStateException(
                "SUT endpoint '%s' baseUrl must include explicit port for %s".formatted(endpointId, scheme));
            default -> throw new IllegalStateException(
                "Unsupported SUT endpoint scheme '%s' for endpoint '%s'".formatted(scheme, endpointId));
        };
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireOrigin(ControlPlaneProperties properties) {
        String instanceId = properties.getInstanceId();
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalStateException("pockethive.control-plane.identity.instance-id must not be null or blank");
        }
        return instanceId.trim();
    }

    private static String toJson(ControlSignal signal) {
        return ControlPlaneJson.write(
            signal,
            "control signal %s for swarm %s".formatted(
                signal.type(), signal.scope() != null ? signal.scope().swarmId() : "n/a"));
    }

    private record EndpointTarget(String clientBaseUrl, String scheme, URI uri) {
    }
}
