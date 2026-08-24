package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EnvironmentHealthServiceTest {
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-21T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    private static final List<EnvironmentHealthTarget> TARGETS = List.of(
        target("pockethive-ui", "PocketHive UI", "/", "/healthz", EnvironmentHealthContract.PLAIN_OK),
        target("orchestrator", "Orchestrator", "/orchestrator/", "/orchestrator/actuator/health",
            EnvironmentHealthContract.SPRING_UP),
        target("scenario-manager", "Scenario Manager", "/scenario-manager/",
            "/scenario-manager/actuator/health", EnvironmentHealthContract.SPRING_UP),
        target("network-proxy-manager", "Network Proxy Manager", "/network-proxy-manager/",
            "/network-proxy-manager/actuator/health", EnvironmentHealthContract.SPRING_UP),
        target("wiremock", "WireMock", "/wiremock/", "/wiremock/__admin/health",
            EnvironmentHealthContract.WIREMOCK_HEALTHY),
        target("tcp-mock", "TCP Mock", "/tcp-mock/", "/tcp-mock/actuator/health",
            EnvironmentHealthContract.SPRING_UP),
        target("grafana", "Grafana", "/grafana/", "/grafana/api/health",
            EnvironmentHealthContract.GRAFANA_DATABASE_OK));

    @Test
    void projectsTheCanonicalIngressTargetsInStableOrderAndMarksOneFailureAsDegraded() {
        List<EnvironmentHealthTarget> observed = new CopyOnWriteArrayList<>();
        EnvironmentHealthService service = new EnvironmentHealthService(
            URI.create("https://nft-lab.example/base-that-must-not-leak"),
            TARGETS,
            target -> {
                observed.add(target);
                return !"wiremock".equals(target.id());
            },
            CLOCK);

        EnvironmentHealthView result = service.read();

        assertThat(observed).extracting(EnvironmentHealthTarget::probePath)
            .containsExactlyInAnyOrder(
                "/healthz",
                "/orchestrator/actuator/health",
                "/scenario-manager/actuator/health",
                "/network-proxy-manager/actuator/health",
                "/wiremock/__admin/health",
                "/tcp-mock/actuator/health",
                "/grafana/api/health");
        assertThat(result.status()).isEqualTo(EnvironmentHealthStatus.DEGRADED);
        assertThat(result.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(result.services()).extracting(EnvironmentServiceHealth::id)
            .containsExactly(
                "pockethive-ui",
                "orchestrator",
                "scenario-manager",
                "network-proxy-manager",
                "wiremock",
                "tcp-mock",
                "grafana");
        assertThat(result.services()).extracting(EnvironmentServiceHealth::name)
            .containsExactly(
                "PocketHive UI",
                "Orchestrator",
                "Scenario Manager",
                "Network Proxy Manager",
                "WireMock",
                "TCP Mock",
                "Grafana");
        assertThat(result.services()).extracting(EnvironmentServiceHealth::endpoint)
            .containsExactly(
                URI.create("https://nft-lab.example/"),
                URI.create("https://nft-lab.example/orchestrator/"),
                URI.create("https://nft-lab.example/scenario-manager/"),
                URI.create("https://nft-lab.example/network-proxy-manager/"),
                URI.create("https://nft-lab.example/wiremock/"),
                URI.create("https://nft-lab.example/tcp-mock/"),
                URI.create("https://nft-lab.example/grafana/"));
        assertThat(result.services()).extracting(EnvironmentServiceHealth::status)
            .containsExactly(
                EnvironmentServiceStatus.HEALTHY,
                EnvironmentServiceStatus.HEALTHY,
                EnvironmentServiceStatus.HEALTHY,
                EnvironmentServiceStatus.HEALTHY,
                EnvironmentServiceStatus.UNAVAILABLE,
                EnvironmentServiceStatus.HEALTHY,
                EnvironmentServiceStatus.HEALTHY);
        assertThat(result.services()).extracting(EnvironmentServiceHealth::observedAt)
            .containsOnly(OBSERVED_AT);
    }

    @Test
    void usesOnlyTheCatalogueSuppliedAtConstruction() {
        EnvironmentHealthTarget declared = target("declared", "Declared", "/declared/", "/health",
            EnvironmentHealthContract.PLAIN_OK);
        EnvironmentHealthView result = new EnvironmentHealthService(
            URI.create("https://nft-lab.example"), List.of(declared), target -> true, CLOCK).read();

        assertThat(result.services()).singleElement().satisfies(service -> {
            assertThat(service.id()).isEqualTo("declared");
            assertThat(service.endpoint()).isEqualTo(URI.create("https://nft-lab.example/declared/"));
        });
    }

    @Test
    void derivesOnlyTheThreeDeclaredAggregateStates() {
        EnvironmentHealthView healthy = service(target -> true).read();
        EnvironmentHealthView unavailable = service(target -> false).read();

        assertThat(healthy.status()).isEqualTo(EnvironmentHealthStatus.HEALTHY);
        assertThat(healthy.services()).allMatch(row -> row.status() == EnvironmentServiceStatus.HEALTHY);
        assertThat(unavailable.status()).isEqualTo(EnvironmentHealthStatus.UNAVAILABLE);
        assertThat(unavailable.services()).allMatch(row -> row.status() == EnvironmentServiceStatus.UNAVAILABLE);
    }

    @Test
    void isolatesAnUnexpectedProbeFailureToItsExactTarget() {
        EnvironmentHealthView result = service(target -> {
            if ("wiremock".equals(target.id())) {
                throw new IllegalStateException("unexpected adapter failure");
            }
            return true;
        }).read();

        assertThat(result.status()).isEqualTo(EnvironmentHealthStatus.DEGRADED);
        assertThat(result.services())
            .filteredOn(row -> row.status() == EnvironmentServiceStatus.UNAVAILABLE)
            .extracting(EnvironmentServiceHealth::id)
            .containsExactly("wiremock");
    }

    @Test
    void mapsAnExecutorFailureToTheCompleteUnavailableProjection() {
        EnvironmentHealthView result = service(target -> {
            if ("pockethive-ui".equals(target.id())) {
                throw new AssertionError("executor task failed");
            }
            return true;
        }).read();

        assertThat(result.status()).isEqualTo(EnvironmentHealthStatus.UNAVAILABLE);
        assertThat(result.services()).hasSize(7)
            .allMatch(row -> row.status() == EnvironmentServiceStatus.UNAVAILABLE);
        assertThat(result.services()).extracting(EnvironmentServiceHealth::observedAt)
            .containsOnly(OBSERVED_AT);
    }

    @Test
    void preservesInterruptionAndReturnsTheCompleteUnavailableProjection() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbes = new CountDownLatch(1);
        Thread reader = Thread.currentThread();
        Thread interrupter = Thread.ofPlatform().daemon().start(() -> {
            await(probeStarted);
            sleep(50);
            reader.interrupt();
            sleep(100);
            releaseProbes.countDown();
        });

        try {
            EnvironmentHealthView result = service(target -> {
                probeStarted.countDown();
                await(releaseProbes);
                return true;
            }).read();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(result.status()).isEqualTo(EnvironmentHealthStatus.UNAVAILABLE);
            assertThat(result.services()).hasSize(7)
                .allMatch(row -> row.status() == EnvironmentServiceStatus.UNAVAILABLE);
        } finally {
            releaseProbes.countDown();
            interrupter.join();
            Thread.interrupted();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("helper thread interrupted", exception);
        }
    }

    private static void sleep(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("helper thread interrupted", exception);
        }
    }

    private static EnvironmentHealthService service(EnvironmentHealthProbePort probe) {
        return new EnvironmentHealthService(URI.create("http://127.0.0.1:8088"), TARGETS, probe, CLOCK);
    }

    private static EnvironmentHealthTarget target(String id, String name, String endpointPath,
                                                   String probePath, EnvironmentHealthContract contract) {
        return new EnvironmentHealthTarget(id, name, URI.create(endpointPath), probePath, contract);
    }
}
