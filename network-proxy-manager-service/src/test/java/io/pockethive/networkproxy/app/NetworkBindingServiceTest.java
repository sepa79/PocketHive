package io.pockethive.networkproxy.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.networkproxy.config.NetworkProxyManagerProperties;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkFault;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.NetworkProfile;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetworkBindingServiceTest {

    @Test
    void bindCreatesProxyAndToxicsForTargetedEndpoint() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "latency-250ms", new NetworkProfile(
                "latency-250ms",
                "Latency 250ms",
                List.of(new NetworkFault("latency", Map.of("latency", 250, "jitter", 25))),
                List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        NetworkBinding binding = service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "latency-250ms",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443"),
                "other", new ResolvedSutEndpoint("other", "TCP", "tcp://proxy.local:9900", "proxy.local:9900", "tcp-upstream:9000")))));

        assertThat(binding.networkMode()).isEqualTo(NetworkMode.PROXIED);
        assertThat(binding.affectedEndpoints()).extracting(ResolvedSutEndpoint::endpointId)
            .containsExactly("payments");
        assertThat(toxiproxy.proxies()).containsKey("sut-a__payments");
        assertThat(toxiproxy.proxies().get("sut-a__payments").listen()).isEqualTo("0.0.0.0:19443");
        assertThat(toxiproxy.toxics("sut-a__payments")).hasSize(1);
        assertThat(toxiproxy.toxics("sut-a__payments").getFirst().type()).isEqualTo("latency");
        assertThat(toxiproxy.toxics("sut-a__payments").getFirst().attributes())
            .containsEntry("latency", 250)
            .containsEntry("jitter", 25);
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void bindCreatesDedicatedProxyRouteForTcpsEndpoint() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("tcps-server"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        NetworkBinding binding = service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "tcps-server", new ResolvedSutEndpoint(
                    "tcps-server",
                    "TCPS",
                    "tcps://proxy.local:9443",
                    "proxy.local:9443",
                    "tls-upstream:9090")))));

        assertThat(binding.affectedEndpoints()).extracting(ResolvedSutEndpoint::endpointId)
            .containsExactly("tcps-server");
        assertThat(toxiproxy.proxies()).containsKey("sut-a__tcps-server");
        assertThat(toxiproxy.proxies().get("sut-a__tcps-server").listen()).isEqualTo("0.0.0.0:19443");
        assertThat(toxiproxy.proxies().get("sut-a__tcps-server").upstream()).isEqualTo("tls-upstream:9090");
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__tcps-server", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void clearReconcilesSharedSutBackToPreviousBinding() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments")),
            "latency-250ms", new NetworkProfile(
                "latency-250ms",
                "Latency 250ms",
                List.of(new NetworkFault("latency", Map.of("latency", 250))),
                List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        service.bind("swarm-b", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "latency-250ms",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        assertThat(toxiproxy.toxics("sut-a__payments")).hasSize(1);

        service.clear("swarm-b", new NetworkBindingClearRequest("sut-a", "ui", "manual clear"));

        assertThat(service.findBinding("swarm-a")).isNotNull();
        assertThat(service.findBinding("swarm-b")).isNull();
        assertThat(toxiproxy.proxies()).containsKey("sut-a__payments");
        assertThat(toxiproxy.toxics("sut-a__payments")).isEmpty();
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void bindRejectsTargetThatDoesNotExistInResolvedSut() {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
                "missing-target", new NetworkProfile("missing-target", "Missing", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        assertThatThrownBy(() -> service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "missing-target",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "other", new ResolvedSutEndpoint("other", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443"))))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("networkProfile target 'payments'");
    }

    @Test
    void clearRejectsMismatchedSutIdAndKeepsBinding() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        assertThatThrownBy(() -> service.clear("swarm-a", new NetworkBindingClearRequest("sut-b", "ui", "manual clear")))
            .isInstanceOf(InvalidNetworkBindingRequestException.class)
            .hasMessageContaining("request.sutId must match existing binding sutId");

        assertThat(service.findBinding("swarm-a")).isNotNull();
        assertThat(toxiproxy.proxies()).containsKey("sut-a__payments");
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void clearRejectsMissingBinding() {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of());
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        assertThatThrownBy(() -> service.clear("missing-swarm", new NetworkBindingClearRequest("sut-a", "ui", "manual clear")))
            .isInstanceOf(NetworkBindingNotFoundException.class)
            .hasMessageContaining("No network binding found for swarmId 'missing-swarm'");
    }

    @Test
    void bindAllowsUpdatingProfileForSameSut() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments")),
            "latency-250ms", new NetworkProfile(
                "latency-250ms",
                "Latency 250ms",
                List.of(new NetworkFault("latency", Map.of("latency", 250, "jitter", 25))),
                List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        NetworkBinding updated = service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "latency-250ms",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        assertThat(updated.sutId()).isEqualTo("sut-a");
        assertThat(updated.networkProfileId()).isEqualTo("latency-250ms");
        assertThat(toxiproxy.toxics("sut-a__payments")).hasSize(1);
        assertThat(toxiproxy.toxics("sut-a__payments").getFirst().type()).isEqualTo("latency");
    }

    @Test
    void bindRejectsChangingSutForExistingSwarm() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        assertThatThrownBy(() -> service.bind("swarm-a", new NetworkBindingRequest(
            "sut-b",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-b", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-b:443"))))))
            .isInstanceOf(InvalidNetworkBindingRequestException.class)
            .hasMessageContaining("changing SUT at runtime is not supported");

        assertThat(service.findBinding("swarm-a")).isNotNull();
        assertThat(service.findBinding("swarm-a").sutId()).isEqualTo("sut-a");
        assertThat(toxiproxy.proxies()).containsOnlyKeys("sut-a__payments");
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void manualOverrideReconcilesWinningBindingsImmediately() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        ManualNetworkOverrideStatus enabled = service.applyManualOverride(new ManualNetworkOverrideRequest(
            true,
            400,
            40,
            2048,
            1200,
            65_536,
            null,
            "hive",
            "live tuning"));

        assertThat(enabled.enabled()).isTrue();
        assertThat(toxiproxy.toxics("sut-a__payments")).extracting(ToxiproxyAdminClient.ToxicRecord::type)
            .containsExactly("latency", "bandwidth", "slow_close", "limit_data");

        ManualNetworkOverrideStatus disabled = service.applyManualOverride(new ManualNetworkOverrideRequest(
            false,
            400,
            40,
            2048,
            1200,
            65_536,
            null,
            "hive",
            "override off"));

        assertThat(disabled.enabled()).isFalse();
        assertThat(toxiproxy.toxics("sut-a__payments")).isEmpty();
    }

    @Test
    void dropConnectionsAddsTransientResetPeerToxic() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        service.dropConnections(new ManualNetworkActionRequest("hive", "drop now"));

        assertThat(toxiproxy.createdTransientResetPeer()).isGreaterThan(0);
        assertThat(toxiproxy.toxics("sut-a__payments")).isEmpty();
    }

    @Test
    void bindMapsSlowCloseAndLimitDataFaults() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "edge-cases", new NetworkProfile(
                "edge-cases",
                "Edge cases",
                List.of(
                    new NetworkFault("slow-close", Map.of("delayMs", 1500)),
                    new NetworkFault("limit-data", Map.of("bytes", 4096))),
                List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FakeHaproxyAdminClient haproxy = new FakeHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "edge-cases",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        assertThat(toxiproxy.toxics("sut-a__payments")).extracting(ToxiproxyAdminClient.ToxicRecord::type)
            .containsExactly("slow_close", "limit_data");
        assertThat(toxiproxy.toxics("sut-a__payments").get(0).attributes()).containsEntry("delay", 1500);
        assertThat(toxiproxy.toxics("sut-a__payments").get(1).attributes()).containsEntry("bytes", 4096);
    }

    @Test
    void bindDoesNotCommitStateWhenReconcileFails() {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FailingHaproxyAdminClient haproxy = new FailingHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        assertThatThrownBy(() -> service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443"))))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("haproxy apply failed");

        assertThat(service.findBinding("swarm-a")).isNull();
    }

    @Test
    void bindRestoresPreviousLiveProxyStateWhenUpdatedBindingFails() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments")),
            "latency-250ms", new NetworkProfile(
                "latency-250ms",
                "Latency 250ms",
                List.of(new NetworkFault("latency", Map.of("latency", 250, "jitter", 25))),
                List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FailOnceHaproxyAdminClient haproxy = new FailOnceHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        haproxy.failNextApply();

        assertThatThrownBy(() -> service.bind("swarm-b", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "latency-250ms",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443"))))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("haproxy apply failed");

        assertThat(service.findBinding("swarm-a")).isNotNull();
        assertThat(service.findBinding("swarm-b")).isNull();
        assertThat(toxiproxy.proxies()).containsOnlyKeys("sut-a__payments");
        assertThat(toxiproxy.toxics("sut-a__payments")).isEmpty();
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    @Test
    void manualOverrideRestoresPreviousStateWhenReconcileFails() throws Exception {
        FakeNetworkProfileClient profileClient = new FakeNetworkProfileClient(Map.of(
            "passthrough", new NetworkProfile("passthrough", "Passthrough", List.of(), List.of("payments"))));
        FakeToxiproxyAdminClient toxiproxy = new FakeToxiproxyAdminClient();
        FailOnceHaproxyAdminClient haproxy = new FailOnceHaproxyAdminClient();
        NetworkBindingService service = new NetworkBindingService(profileClient, toxiproxy, haproxy, properties());

        service.bind("swarm-a", new NetworkBindingRequest(
            "sut-a",
            NetworkMode.PROXIED,
            "passthrough",
            "orchestrator",
            null,
            resolvedSut("sut-a", Map.of(
                "payments", new ResolvedSutEndpoint("payments", "HTTPS", "https://proxy.local:9443", "proxy.local:9443", "internal-a:443")))));

        haproxy.failNextApply();

        assertThatThrownBy(() -> service.applyManualOverride(new ManualNetworkOverrideRequest(
            true,
            400,
            40,
            2048,
            1200,
            65_536,
            null,
            "hive",
            "live tuning")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("haproxy apply failed");

        assertThat(service.manualOverride().enabled()).isFalse();
        assertThat(toxiproxy.proxies()).containsOnlyKeys("sut-a__payments");
        assertThat(toxiproxy.toxics("sut-a__payments")).isEmpty();
        assertThat(haproxy.routes()).containsExactly(
            new HaproxyAdminClient.RouteRecord("sut-a__payments", "0.0.0.0:9443", "toxiproxy:19443"));
    }

    private static NetworkProxyManagerProperties properties() {
        NetworkProxyManagerProperties properties = new NetworkProxyManagerProperties();
        properties.getToxiproxy().setListenHost("0.0.0.0");
        properties.getToxiproxy().setListenPortOffset(10_000);
        properties.getHaproxy().setBackendHost("toxiproxy");
        return properties;
    }

    private static ResolvedSutEnvironment resolvedSut(String sutId, Map<String, ResolvedSutEndpoint> endpoints) {
        return new ResolvedSutEnvironment(sutId, "SUT", null, endpoints);
    }

    private static final class FakeNetworkProfileClient implements NetworkProfileClient {
        private final Map<String, NetworkProfile> profiles;

        private FakeNetworkProfileClient(Map<String, NetworkProfile> profiles) {
            this.profiles = profiles;
        }

        @Override
        public NetworkProfile fetch(String profileId) {
            NetworkProfile profile = profiles.get(profileId);
            if (profile == null) {
                throw new IllegalStateException("missing profile " + profileId);
            }
            return profile;
        }
    }

    private static final class FakeToxiproxyAdminClient implements ToxiproxyAdminClient {
        private final Map<String, ProxyRecord> proxies = new LinkedHashMap<>();
        private final Map<String, List<ToxicRecord>> toxics = new LinkedHashMap<>();
        private int transientResetPeerCount = 0;

        @Override
        public Map<String, ProxyRecord> listProxies() {
            return new LinkedHashMap<>(proxies);
        }

        @Override
        public void deleteProxy(String proxyName) {
            proxies.remove(proxyName);
            toxics.remove(proxyName);
        }

        @Override
        public ProxyRecord createProxy(ProxyRecord proxy) {
            proxies.put(proxy.name(), proxy);
            toxics.put(proxy.name(), new ArrayList<>());
            return proxy;
        }

        @Override
        public ToxicRecord createToxic(String proxyName, ToxicRecord toxic) {
            toxics.computeIfAbsent(proxyName, ignored -> new ArrayList<>()).add(toxic);
            if ("reset_peer".equals(toxic.type()) && toxic.name().startsWith("manual-drop-")) {
                transientResetPeerCount++;
            }
            return toxic;
        }

        @Override
        public void deleteToxic(String proxyName, String toxicName) {
            toxics.computeIfAbsent(proxyName, ignored -> new ArrayList<>())
                .removeIf(toxic -> toxic.name().equals(toxicName));
        }

        private Map<String, ProxyRecord> proxies() {
            return proxies;
        }

        private List<ToxicRecord> toxics(String proxyName) {
            return toxics.getOrDefault(proxyName, List.of());
        }

        private int createdTransientResetPeer() {
            return transientResetPeerCount;
        }
    }

    private static class FakeHaproxyAdminClient implements HaproxyAdminClient {
        private List<RouteRecord> routes = List.of();

        @Override
        public void applyRoutes(List<RouteRecord> routes) {
            this.routes = routes == null ? List.of() : List.copyOf(routes);
        }

        protected List<RouteRecord> routes() {
            return routes;
        }
    }

    private static final class FailingHaproxyAdminClient extends FakeHaproxyAdminClient {
        @Override
        public void applyRoutes(List<RouteRecord> routes) {
            throw new IllegalStateException("haproxy apply failed");
        }
    }

    private static final class FailOnceHaproxyAdminClient extends FakeHaproxyAdminClient {
        private boolean failNextApply;

        private void failNextApply() {
            failNextApply = true;
        }

        @Override
        public void applyRoutes(List<RouteRecord> routes) {
            if (failNextApply) {
                failNextApply = false;
                throw new IllegalStateException("haproxy apply failed");
            }
            super.applyRoutes(routes);
        }
    }
}
