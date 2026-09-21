package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.e2e.clients.AuthServiceClient;
import io.pockethive.e2e.clients.NetworkProxyManagerClient;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import io.pockethive.swarm.model.ResolvedSutEnvironment;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Exercises the Network Proxy Manager's HAProxy desired/applied handshake through the public ingress.
 * The test uses a dedicated synthetic SUT and clears its binding in an {@link After} hook.
 */
public final class HaproxyNfsSteps {

  private static final String PROFILE_ID = "passthrough";
  private static final String REQUESTED_BY = "e2e-haproxy-nfs";
  private static final String SYNTHETIC_SUT_ID = "e2e-haproxy-nfs-sut";
  private static final int FIRST_CANDIDATE_PORT = 28080;
  private static final int LAST_CANDIDATE_PORT = 28179;
  private static final Duration EXPECTED_APPLY_TIMEOUT = Duration.ofSeconds(10);

  private NetworkProxyManagerClient networkProxyManager;
  private String swarmId;
  private NetworkBinding previousValidBinding;
  private WebClientResponseException rejectedCandidate;
  private Duration rejectionElapsed;
  private int validPort;
  private boolean validBindingActive;

  @Given("the HAProxy NFS acceptance harness is initialised")
  public void initialiseHarness() {
    var endpoints = EnvironmentConfig.loadServiceEndpoints();
    AuthServiceClient authServiceClient = AuthServiceClient.create(endpoints.auth().authServiceBaseUrl());
    String bearerToken = endpoints.auth().accessToken()
        .orElseGet(() -> authServiceClient.devLogin(endpoints.auth().username()));
    networkProxyManager = NetworkProxyManagerClient.create(
        endpoints.networkProxyManagerBaseUrl(), bearerToken);
    clearStaleAcceptanceBindings();
    swarmId = "e2e-haproxy-nfs-" + UUID.randomUUID();
    validPort = selectAvailablePort();
  }

  @When("I apply a valid HAProxy NFS binding")
  public void applyValidBinding() {
    previousValidBinding = networkProxyManager.bind(swarmId, bindingRequest(validPort));
    validBindingActive = true;
  }

  @Then("the cross-node HAProxy handshake confirms the valid binding")
  public void assertValidBinding() {
    assertNotNull(previousValidBinding, "Expected Network Proxy Manager binding response");
    assertEquals(NetworkMode.PROXIED, previousValidBinding.effectiveMode());
    assertEquals("haproxy:" + validPort,
        previousValidBinding.affectedEndpoints().getFirst().clientAuthority());
    NetworkBinding current = networkProxyManager.findBinding(swarmId).orElseThrow(
        () -> new AssertionError("Expected the acknowledged binding to be retained"));
    assertEquals(previousValidBinding, current);
  }

  @When("I apply a deliberately invalid HAProxy candidate")
  public void applyInvalidCandidate() {
    long startedAt = System.nanoTime();
    rejectedCandidate = assertThrows(WebClientResponseException.class,
        () -> networkProxyManager.bind(swarmId, bindingRequest(-1)));
    rejectionElapsed = Duration.ofNanos(System.nanoTime() - startedAt);
  }

  @Then("the candidate times out and the previous valid HAProxy binding remains active")
  public void assertRejectedCandidateRollback() {
    assertNotNull(rejectedCandidate, "Expected invalid HAProxy candidate to be rejected");
    assertTrue(rejectedCandidate.getStatusCode().is5xxServerError(),
        () -> "Expected explicit HAProxy apply failure, got HTTP " + rejectedCandidate.getStatusCode());
    assertNotNull(rejectionElapsed, "Expected HAProxy apply duration");
    assertTrue(rejectionElapsed.compareTo(EXPECTED_APPLY_TIMEOUT.minusSeconds(1)) >= 0,
        () -> "Expected HAProxy apply acknowledgement timeout near " + EXPECTED_APPLY_TIMEOUT
            + ", but failure returned after " + rejectionElapsed);

    NetworkBinding current = networkProxyManager.findBinding(swarmId).orElseThrow(
        () -> new AssertionError("Previous valid binding disappeared after candidate rejection"));
    assertEquals(previousValidBinding, current,
        "Rejected candidate must not replace the previous acknowledged binding");
  }

  @When("I clear the HAProxy NFS acceptance binding")
  public void clearBinding() {
    NetworkBinding cleared = networkProxyManager.clear(
        swarmId, new NetworkBindingClearRequest(SYNTHETIC_SUT_ID, REQUESTED_BY, "e2e cleanup"));
    assertEquals(NetworkMode.DIRECT, cleared.effectiveMode());
    validBindingActive = false;
  }

  @Then("the HAProxy NFS acceptance binding is absent")
  public void assertBindingAbsent() {
    assertTrue(networkProxyManager.findBinding(swarmId).isEmpty(),
        "Expected no binding after HAProxy NFS acceptance cleanup");
  }

  @After(order = 100)
  public void cleanupBinding() {
    if (!validBindingActive || networkProxyManager == null || swarmId == null) {
      return;
    }
    try {
      networkProxyManager.clear(
          swarmId, new NetworkBindingClearRequest(SYNTHETIC_SUT_ID, REQUESTED_BY, "e2e failure cleanup"));
      validBindingActive = false;
    } catch (WebClientResponseException.NotFound ignored) {
      validBindingActive = false;
    }
  }

  private static NetworkBindingRequest bindingRequest(int port) {
    String authority = "haproxy:" + port;
    ResolvedSutEndpoint endpoint = new ResolvedSutEndpoint(
        "default",
        "HTTP",
        "http://" + authority,
        authority,
        "wiremock:8080");
    return new NetworkBindingRequest(
        SYNTHETIC_SUT_ID,
        NetworkMode.PROXIED,
        PROFILE_ID,
        REQUESTED_BY,
        "e2e HAProxy NFS acceptance",
        new ResolvedSutEnvironment(
            SYNTHETIC_SUT_ID,
            "HAProxy NFS E2E synthetic SUT",
            "http",
            Map.of(endpoint.endpointId(), endpoint)));
  }

  private void clearStaleAcceptanceBindings() {
    networkProxyManager.listBindings().stream()
        .filter(this::isAcceptanceBinding)
        .forEach(binding -> networkProxyManager.clear(
            binding.swarmId(),
            new NetworkBindingClearRequest(SYNTHETIC_SUT_ID, REQUESTED_BY, "stale e2e cleanup")));
  }

  private int selectAvailablePort() {
    var bindings = networkProxyManager.listBindings();
    return IntStream.rangeClosed(FIRST_CANDIDATE_PORT, LAST_CANDIDATE_PORT)
        .filter(port -> bindings.stream().noneMatch(binding -> usesPort(binding, port)))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No free HAProxy NFS acceptance port in range "
                + FIRST_CANDIDATE_PORT + "-" + LAST_CANDIDATE_PORT));
  }

  private boolean isAcceptanceBinding(NetworkBinding binding) {
    return SYNTHETIC_SUT_ID.equals(binding.sutId())
        && REQUESTED_BY.equals(binding.requestedBy());
  }

  private static boolean usesPort(NetworkBinding binding, int port) {
    return binding.affectedEndpoints().stream()
        .anyMatch(endpoint -> endpoint.clientAuthority().endsWith(":" + port));
  }
}
