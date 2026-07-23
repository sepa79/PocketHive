package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ControlResponseFactoryTest {

  @Test
  void createsTheCanonicalResponseForEveryController() {
    ControlPlaneProperties properties = new ControlPlaneProperties();
    properties.setInstanceId("orch-1");
    ControlResponseFactory factory = new ControlResponseFactory(properties);
    Instant now = Instant.parse("2026-07-23T10:00:00Z");
    SwarmOperation operation = SwarmOperation.accepted(
        "swarm-1",
        OperationType.CONFIG_UPDATE,
        new Target("generator", "gen-1"),
        "corr-1",
        "idem-1",
        now,
        now.plusSeconds(60));

    ControlResponse response = factory.create(operation, Duration.ofSeconds(60));

    assertThat(response.correlationId()).isEqualTo("corr-1");
    assertThat(response.idempotencyKey()).isEqualTo("idem-1");
    assertThat(response.operationUrl()).isEqualTo("/api/swarms/swarm-1/operations/corr-1");
    assertThat(response.outcomeTopic())
        .isEqualTo("event.outcome.%s.swarm-1.orchestrator.orch-1".formatted(ControlPlaneSignals.CONFIG_UPDATE));
    assertThat(response.timeoutMs()).isEqualTo(60_000L);
  }
}
