package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.config.ProxyTarget;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import java.net.URI;

/**
 * Responsibility: compare an owned binding with its explicit authored SUT endpoint.
 * Must not: resolve addresses, create swarms or mutate/clear network bindings.
 * Contract: docs/architecture/acceptance-tests.md#network-acceptance-extension-nw-2nw-3nw-5.
 */
final class ProxyAssertions {
  private ProxyAssertions() {}
  static ResolvedSutEndpoint requireBinding(LiveRun run, String swarmId, ProxyTarget target,
                                           SutEnvironment sut, String scheme) throws Exception {
    assertEquals(run.target.fixture().sutId(), sut.id());
    var endpoint = sut.endpoints().get(target.endpointId());
    assertNotNull(endpoint, "Selected endpoint must belong to the explicit bundle SUT");
    assertEquals(scheme, endpoint.kind());
    assertNotNull(endpoint.upstreamBaseUrl(), "Proxy fixture requires an explicit upstream");
    var client = URI.create(endpoint.baseUrl());
    var upstream = URI.create(endpoint.upstreamBaseUrl());
    assertEquals(scheme, client.getScheme());
    assertEquals(scheme, upstream.getScheme());
    assertNotEquals(client.getAuthority(), upstream.getAuthority(), "Fixture must exercise a proxy hop");
    var binding = run.networkBindings.requireBinding(swarmId);
    run.evidence.record("binding", binding);
    assertAll("Selected network binding",
        () -> assertEquals(swarmId, binding.swarmId()),
        () -> assertEquals(sut.id(), binding.sutId()),
        () -> assertEquals(NetworkMode.PROXIED, binding.networkMode()),
        () -> assertEquals(NetworkMode.PROXIED, binding.effectiveMode()),
        () -> assertEquals(target.networkProfileId(), binding.networkProfileId()));
    assertEquals(1, binding.affectedEndpoints().size(), "Proxy fixture selects one endpoint");
    var bound = binding.affectedEndpoints().getFirst();
    assertAll("Authored proxy endpoint",
        () -> assertEquals(target.endpointId(), bound.endpointId()),
        () -> assertEquals(endpoint.kind(), bound.kind()),
        () -> assertEquals(endpoint.baseUrl(), bound.clientBaseUrl()),
        () -> assertEquals(client.getAuthority(), bound.clientAuthority()),
        () -> assertEquals(upstream.getAuthority(), bound.upstreamAuthority()));
    return bound;
  }
}
