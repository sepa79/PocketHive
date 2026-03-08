package io.pockethive.networkproxy.app;

import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkFault;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.NetworkProfile;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class NetworkBindingService {
    private static final Logger log = LoggerFactory.getLogger(NetworkBindingService.class);

    private final Map<String, NetworkBinding> bindings = new ConcurrentHashMap<>();
    private final NetworkProfileClient profileClient;
    private final ToxiproxyAdminClient toxiproxy;
    private final HaproxyAdminClient haproxy;
    private final String toxiproxyListenHost;
    private final String haproxyBackendHost;
    private final int toxiproxyListenPortOffset;

    public NetworkBindingService(NetworkProfileClient profileClient,
                                 ToxiproxyAdminClient toxiproxy,
                                 HaproxyAdminClient haproxy,
                                 NetworkProxyManagerProperties properties) {
        this.profileClient = profileClient;
        this.toxiproxy = toxiproxy;
        this.haproxy = haproxy;
        this.toxiproxyListenHost = requireText(properties.getToxiproxy().getListenHost(),
            "pockethive.network-proxy-manager.toxiproxy.listen-host");
        this.haproxyBackendHost = requireText(properties.getHaproxy().getBackendHost(),
            "pockethive.network-proxy-manager.haproxy.backend-host");
        this.toxiproxyListenPortOffset = requirePositivePortOffset(properties.getToxiproxy().getListenPortOffset());
    }

    public List<NetworkBinding> listBindings() {
        return bindings.values().stream()
            .sorted(Comparator.comparing(NetworkBinding::appliedAt).reversed())
            .toList();
    }

    public List<NetworkBinding> listProxies() {
        return bindings.values().stream()
            .filter(binding -> binding.effectiveMode() == NetworkMode.PROXIED)
            .sorted(Comparator.comparing(NetworkBinding::appliedAt).reversed())
            .toList();
    }

    public NetworkBinding findBinding(String swarmId) {
        return bindings.get(trimmedSwarmId(swarmId));
    }

    public synchronized NetworkBinding bind(String swarmId, NetworkBindingRequest request) throws Exception {
        String id = trimmedSwarmId(swarmId);
        Objects.requireNonNull(request, "request");
        if (request.networkMode() != NetworkMode.PROXIED) {
            throw new IllegalArgumentException("bind endpoint requires networkMode=PROXIED; use /clear for DIRECT");
        }
        ResolvedSutEnvironment resolvedSut = Objects.requireNonNull(request.resolvedSut(),
            "resolvedSut must be provided when networkMode=PROXIED");
        NetworkProfile profile = profileClient.fetch(request.networkProfileId());
        List<ResolvedSutEndpoint> affectedEndpoints = selectAffectedEndpoints(profile, resolvedSut);
        NetworkBinding binding = new NetworkBinding(
            id,
            request.sutId(),
            request.networkMode(),
            request.networkProfileId(),
            request.networkMode(),
            request.requestedBy(),
            Instant.now(),
            affectedEndpoints);
        bindings.put(id, binding);
        reconcileSut(request.sutId());
        return binding;
    }

    public synchronized NetworkBinding clear(String swarmId, NetworkBindingClearRequest request) throws Exception {
        String id = trimmedSwarmId(swarmId);
        Objects.requireNonNull(request, "request");
        bindings.remove(id);
        reconcileSut(request.sutId());
        return new NetworkBinding(
            id,
            request.sutId(),
            NetworkMode.DIRECT,
            null,
            NetworkMode.DIRECT,
            request.requestedBy(),
            Instant.now(),
            List.of());
    }

    private void reconcileSut(String sutId) throws Exception {
        String trimmedSutId = requireText(sutId, "sutId");
        List<NetworkBinding> activeBindings = bindings.values().stream()
            .filter(binding -> binding.sutId().equals(trimmedSutId))
            .filter(binding -> binding.networkMode() == NetworkMode.PROXIED)
            .sorted(Comparator.comparing(NetworkBinding::appliedAt))
            .toList();
        Map<String, ToxiproxyAdminClient.ProxyRecord> existingProxies = toxiproxy.listProxies();
        deleteManagedProxies(trimmedSutId, existingProxies);
        if (activeBindings.isEmpty()) {
            haproxy.applyRoutes(routesForWinningBindings());
            log.info("cleared all proxied listeners for sut={}", trimmedSutId);
            return;
        }
        NetworkBinding winner = activeBindings.get(activeBindings.size() - 1);
        NetworkProfile profile = profileClient.fetch(winner.networkProfileId());
        Map<String, ToxiproxyAdminClient.ProxyRecord> remainingProxies = toxiproxy.listProxies();
        validatePortConflicts(trimmedSutId, winner.affectedEndpoints(), remainingProxies);
        for (ResolvedSutEndpoint endpoint : winner.affectedEndpoints()) {
            String proxyName = proxyName(trimmedSutId, endpoint.endpointId());
            ToxiproxyAdminClient.ProxyRecord proxy = new ToxiproxyAdminClient.ProxyRecord(
                proxyName,
                toxiproxyListenAddress(endpoint),
                endpoint.upstreamAuthority(),
                true);
            toxiproxy.createProxy(proxy);
            for (ToxiproxyAdminClient.ToxicRecord toxic : mapFaults(profile, endpoint)) {
                toxiproxy.createToxic(proxyName, toxic);
            }
        }
        haproxy.applyRoutes(routesForWinningBindings());
        log.info("reconciled proxied listeners for sut={} using swarm={} profile={} endpoints={}",
            trimmedSutId, winner.swarmId(), winner.networkProfileId(), winner.affectedEndpoints().size());
    }

    private void deleteManagedProxies(String sutId,
                                      Map<String, ToxiproxyAdminClient.ProxyRecord> existingProxies) throws Exception {
        String prefix = proxyNamePrefix(sutId);
        for (String proxyName : existingProxies.keySet()) {
            if (proxyName.startsWith(prefix)) {
                toxiproxy.deleteProxy(proxyName);
            }
        }
    }

    private void validatePortConflicts(String sutId,
                                       List<ResolvedSutEndpoint> desiredEndpoints,
                                       Map<String, ToxiproxyAdminClient.ProxyRecord> existingProxies) {
        Map<Integer, String> occupiedPorts = new LinkedHashMap<>();
        for (ToxiproxyAdminClient.ProxyRecord proxy : existingProxies.values()) {
            occupiedPorts.put(parsePort(proxy.listen()), proxy.name());
        }
        for (ResolvedSutEndpoint endpoint : desiredEndpoints) {
            int port = toxiproxyListenPort(endpoint);
            String conflictingProxy = occupiedPorts.get(port);
            if (conflictingProxy != null) {
                throw new IllegalStateException("toxiproxy listen port " + port
                    + " for endpoint '" + endpoint.endpointId() + "' of sut '" + sutId
                    + "' conflicts with active proxy '" + conflictingProxy + "'");
            }
        }
    }

    private List<HaproxyAdminClient.RouteRecord> routesForWinningBindings() {
        Map<Integer, String> occupiedFrontendPorts = new LinkedHashMap<>();
        return bindings.values().stream()
            .filter(binding -> binding.networkMode() == NetworkMode.PROXIED)
            .collect(java.util.stream.Collectors.groupingBy(
                NetworkBinding::sutId,
                LinkedHashMap::new,
                java.util.stream.Collectors.maxBy(Comparator.comparing(NetworkBinding::appliedAt))))
            .values().stream()
            .flatMap(java.util.Optional::stream)
            .sorted(Comparator.comparing(NetworkBinding::sutId))
            .flatMap(binding -> binding.affectedEndpoints().stream()
                .sorted(Comparator.comparing(ResolvedSutEndpoint::endpointId))
                .map(endpoint -> toHaproxyRoute(binding.sutId(), endpoint, occupiedFrontendPorts)))
            .toList();
    }

    private HaproxyAdminClient.RouteRecord toHaproxyRoute(String sutId,
                                                          ResolvedSutEndpoint endpoint,
                                                          Map<Integer, String> occupiedFrontendPorts) {
        int frontendPort = parsePort(endpoint.clientAuthority());
        String conflict = occupiedFrontendPorts.putIfAbsent(frontendPort, sutId + ":" + endpoint.endpointId());
        if (conflict != null) {
            throw new IllegalStateException("haproxy frontend port " + frontendPort
                + " for endpoint '" + endpoint.endpointId() + "' of sut '" + sutId
                + "' conflicts with active route '" + conflict + "'");
        }
        return new HaproxyAdminClient.RouteRecord(
            proxyName(sutId, endpoint.endpointId()),
            toxiproxyListenHost + ":" + frontendPort,
            haproxyBackendHost + ":" + toxiproxyListenPort(endpoint));
    }

    private List<ResolvedSutEndpoint> selectAffectedEndpoints(NetworkProfile profile,
                                                              ResolvedSutEnvironment resolvedSut) {
        Map<String, ResolvedSutEndpoint> endpoints = resolvedSut.endpoints();
        if (endpoints.isEmpty()) {
            throw new IllegalArgumentException("resolvedSut must define at least one endpoint");
        }
        List<String> targets = profile.targets();
        if (targets.isEmpty()) {
            return endpoints.values().stream()
                .sorted(Comparator.comparing(ResolvedSutEndpoint::endpointId))
                .toList();
        }
        Set<String> seenTargets = new LinkedHashSet<>();
        List<ResolvedSutEndpoint> selected = new ArrayList<>();
        for (String target : targets) {
            String endpointId = requireText(target, "networkProfile.targets[]");
            if (!seenTargets.add(endpointId)) {
                continue;
            }
            ResolvedSutEndpoint endpoint = endpoints.get(endpointId);
            if (endpoint == null) {
                throw new IllegalArgumentException("networkProfile target '" + endpointId
                    + "' is not defined in resolvedSut '" + resolvedSut.sutId() + "'");
            }
            selected.add(endpoint);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("networkProfile selected no endpoints for resolvedSut '" + resolvedSut.sutId() + "'");
        }
        selected.sort(Comparator.comparing(ResolvedSutEndpoint::endpointId));
        return List.copyOf(selected);
    }

    private List<ToxiproxyAdminClient.ToxicRecord> mapFaults(NetworkProfile profile, ResolvedSutEndpoint endpoint) {
        List<ToxiproxyAdminClient.ToxicRecord> toxics = new ArrayList<>();
        int index = 0;
        for (NetworkFault fault : profile.faults()) {
            NetworkFaultType type = NetworkFaultType.fromId(fault.type());
            String toxicName = endpoint.endpointId() + "-" + type.name().toLowerCase() + "-" + index++;
            toxics.add(switch (type) {
                case LATENCY -> new ToxiproxyAdminClient.ToxicRecord(
                    toxicName,
                    "latency",
                    streamOf(fault, "downstream"),
                    toxicityOf(fault),
                    Map.of(
                        "latency", requireInt(fault.config(), "latency"),
                        "jitter", intOrDefault(fault.config(), "jitter", 0)));
                case BANDWIDTH -> new ToxiproxyAdminClient.ToxicRecord(
                    toxicName,
                    "bandwidth",
                    streamOf(fault, "downstream"),
                    toxicityOf(fault),
                    Map.of("rate", requireInt(fault.config(), "rateKbps")));
                case TIMEOUT -> new ToxiproxyAdminClient.ToxicRecord(
                    toxicName,
                    "timeout",
                    streamOf(fault, "downstream"),
                    toxicityOf(fault),
                    Map.of("timeout", requireInt(fault.config(), "timeoutMs")));
                case RESET_PEER -> new ToxiproxyAdminClient.ToxicRecord(
                    toxicName,
                    "reset_peer",
                    streamOf(fault, "downstream"),
                    toxicityOf(fault),
                    Map.of("timeout", intOrDefault(fault.config(), "timeoutMs", 0)));
            });
        }
        return List.copyOf(toxics);
    }

    private String toxiproxyListenAddress(ResolvedSutEndpoint endpoint) {
        return toxiproxyListenHost + ":" + toxiproxyListenPort(endpoint);
    }

    private int toxiproxyListenPort(ResolvedSutEndpoint endpoint) {
        int clientPort = parsePort(endpoint.clientAuthority());
        int port = clientPort + toxiproxyListenPortOffset;
        if (port > 65_535) {
            throw new IllegalStateException("toxiproxy listen port exceeds 65535 for clientAuthority '"
                + endpoint.clientAuthority() + "'");
        }
        return port;
    }

    private static String streamOf(NetworkFault fault, String defaultValue) {
        Object stream = fault.config().get("stream");
        if (stream == null) {
            return defaultValue;
        }
        String value = String.valueOf(stream).trim();
        if (!value.equals("upstream") && !value.equals("downstream")) {
            throw new IllegalArgumentException("Unsupported toxic stream: " + value);
        }
        return value;
    }

    private static double toxicityOf(NetworkFault fault) {
        Object toxicity = fault.config().get("toxicity");
        if (toxicity == null) {
            return 1.0d;
        }
        if (toxicity instanceof Number number) {
            double value = number.doubleValue();
            if (value <= 0.0d || value > 1.0d) {
                throw new IllegalArgumentException("toxicity must be in range (0.0, 1.0]");
            }
            return value;
        }
        throw new IllegalArgumentException("toxicity must be numeric");
    }

    private static int requireInt(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        if (raw == null) {
            throw new IllegalArgumentException("fault.config." + key + " must be provided");
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw).trim());
    }

    private static int intOrDefault(Map<String, Object> config, String key, int defaultValue) {
        Object raw = config.get(key);
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(raw).trim());
    }

    private static int parsePort(String authority) {
        String value = requireText(authority, "authority");
        int colon = value.lastIndexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException("authority must include host:port, got '" + value + "'");
        }
        return Integer.parseInt(value.substring(colon + 1));
    }

    private static String proxyName(String sutId, String endpointId) {
        return proxyNamePrefix(sutId) + sanitize(endpointId);
    }

    private static String proxyNamePrefix(String sutId) {
        return sanitize(sutId) + "__";
    }

    private static String sanitize(String value) {
        String normalized = requireText(value, "value").toLowerCase();
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                out.append(ch);
            } else {
                out.append('-');
            }
        }
        return out.toString();
    }

    private static String trimmedSwarmId(String swarmId) {
        return requireText(swarmId, "swarmId");
    }

    private static int requirePositivePortOffset(int value) {
        if (value <= 0) {
            throw new IllegalStateException("pockethive.network-proxy-manager.toxiproxy.listen-port-offset must be positive");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
