package io.pockethive.swarmcontroller.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.swarm.model.SutEndpoint;
import io.pockethive.swarm.model.SutEnvironment;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SwarmRuntimeCoreSutConfigTest {

  @Test
  void enrichesSutConfigUsingCanonicalEndpointMapKey() throws Exception {
    SutEndpoint endpoint = new SutEndpoint("HTTP", "http://wiremock:8080", null);
    SutEnvironment environment = new SutEnvironment(
        "wiremock-local",
        "WireMock local",
        "sandbox",
        Map.of("default", endpoint));
    Map<String, Object> config = Map.of(
        "baseUrl", "http://legacy.invalid",
        "sut", Map.of("targetEndpointId", "default"));

    Method method = SwarmRuntimeCore.class.getDeclaredMethod(
        "enrichConfigWithSut", Map.class, SutEnvironment.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> enriched = (Map<String, Object>) method.invoke(null, config, environment);

    assertThat(enriched.get("baseUrl")).isEqualTo("http://wiremock:8080");
    assertThat(enriched.get("sut")).isInstanceOf(Map.class);
    Map<?, ?> sut = (Map<?, ?>) enriched.get("sut");
    assertThat(sut.get("targetEndpointId")).isEqualTo("default");
    assertThat(sut.get("targetEndpoint")).isEqualTo(endpoint);
    assertThat(sut.get("environment")).isEqualTo(environment);
    assertThat(sut.get("environmentId")).isEqualTo("wiremock-local");
    assertThat(sut.get("environmentType")).isEqualTo("sandbox");
  }

  @Test
  void omitsStaleEnvironmentTypeWhenCanonicalSutHasNoType() throws Exception {
    SutEndpoint endpoint = new SutEndpoint("TCP", "tcp://tcp-mock-server:9090", null);
    SutEnvironment environment = new SutEnvironment(
        "tcp-mock-local",
        "TCP Mock local",
        null,
        Map.of("tcp-server", endpoint));
    Map<String, Object> config = Map.of(
        "sut", Map.of(
            "targetEndpointId", "tcp-server",
            "environmentType", "stale"));

    Map<String, Object> enriched = enrich(config, environment);

    assertThat(enriched.get("sut")).isInstanceOf(Map.class);
    Map<?, ?> sut = (Map<?, ?>) enriched.get("sut");
    assertThat(sut.get("targetEndpointId")).isEqualTo("tcp-server");
    assertThat(sut.containsKey("environmentType")).isFalse();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> enrich(Map<String, Object> config,
                                             SutEnvironment environment) throws Exception {
    Method method = SwarmRuntimeCore.class.getDeclaredMethod(
        "enrichConfigWithSut", Map.class, SutEnvironment.class);
    method.setAccessible(true);
    return (Map<String, Object>) method.invoke(null, config, environment);
  }
}
