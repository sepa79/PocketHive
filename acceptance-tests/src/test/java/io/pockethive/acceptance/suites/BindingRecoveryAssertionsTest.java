package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.api.ApiException;
import io.pockethive.acceptance.api.ApiResponse;
import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.ResolvedSutEndpoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BindingRecoveryAssertionsTest {
  private static final Instant APPLIED = Instant.parse("2026-09-18T12:00:00Z");
  private static final Duration MINIMUM = Duration.ofSeconds(9);
  private static final Duration ELAPSED = Duration.ofSeconds(10);
  private static NetworkBinding binding(Instant applied, String authority) {
    return new NetworkBinding("owned", "owned-sut", NetworkMode.PROXIED, "profile", NetworkMode.PROXIED,
        "tester", applied, List.of(new ResolvedSutEndpoint("selected", "http", "http://proxy:18090", authority, "sut:8080")));
  }
  private static ApiResponse response(int status) {
    return new ApiResponse("POST", "/network-proxy-manager/api/network/bindings/owned", status, "response", null);
  }
  @Test void acceptsTimedApplyFailureWithExactlyTheSameRetainedBinding() {
    var previous = binding(APPLIED, "proxy:18090");
    BindingRecoveryAssertions.requireRejectedAndPreserved(previous, response(500), ELAPSED, MINIMUM, previous);
  }
  @ParameterizedTest @ValueSource(ints = {200, 400, 401, 403, 409, 502})
  void unrelatedResponsesCannotPassAsApplyRejection(int status) {
    var previous = binding(APPLIED, "proxy:18090");
    assertThrows(ApiException.class, () -> BindingRecoveryAssertions.requireRejectedAndPreserved(
        previous, response(status), ELAPSED, MINIMUM, previous));
  }
  @Test void immediateServerFailureCannotPassAsApplyRejection() {
    var previous = binding(APPLIED, "proxy:18090");
    assertThrows(AssertionError.class, () -> BindingRecoveryAssertions.requireRejectedAndPreserved(
        previous, response(500), Duration.ofMillis(100), MINIMUM, previous));
  }
  @ParameterizedTest @MethodSource("changedBindings")
  void changedBindingOrApplyTimestampCannotPassAsPreservation(NetworkBinding changed) {
    assertThrows(AssertionError.class, () -> BindingRecoveryAssertions.requireRejectedAndPreserved(
        binding(APPLIED, "proxy:18090"), response(500), ELAPSED, MINIMUM, changed));
  }
  static Stream<NetworkBinding> changedBindings() {
    return Stream.of(binding(APPLIED, "invalid:-1"), binding(APPLIED.plusSeconds(1), "proxy:18090"));
  }
}
