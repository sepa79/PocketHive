package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pockethive.acceptance.api.ApiSurface;
import io.pockethive.acceptance.config.TargetLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Responsibility: assert existing platform health responses through public ingress.
 * Must not: infer topology, aggregate health or require an empty deployment.
 * Contract: docs/architecture/acceptance-tests.md#platform-smoke-sm-1.
 */
@Tag("smoke")
class PlatformSmokeAcceptanceIT {
  @Test void ingressReportsPlatformAvailability() throws Exception {
    var target = TargetLoader.loadApi(TargetLoader.selectedFile());
    try (var run = ApiRun.open(target, "platform-smoke")) {
      var ui = run.http.request("GET", "/healthz", null, run.token, "text/plain");
      run.evidence.record("ui-health", ui);
      assertEquals(200, ui.status(), ui.toString());
      assertEquals("ok", ui.body().trim());
      for (var service : new ApiSurface[] {ApiSurface.ORCHESTRATOR, ApiSurface.SCENARIO_MANAGER}) {
        var response = run.http.request("GET", service.publicPath("/actuator/health"), null, run.token);
        run.evidence.record(service.name().toLowerCase(java.util.Locale.ROOT) + "-health", response);
        assertEquals(200, response.status(), response.toString());
        assertEquals("UP", run.http.tree(response).path("status").asText(), response.toString());
      }
    }
  }
}
