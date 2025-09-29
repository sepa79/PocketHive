package io.pockethive.controlplane.payload;

import static io.pockethive.controlplane.payload.JsonFixtureAssertions.assertMatchesFixture;

import io.pockethive.control.CommandState;
import io.pockethive.control.ConfirmationScope;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfirmationPayloadFactoryTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);
    private final ScopeContext scope = ScopeContext.of(new ConfirmationScope("swarm-1", "processor", "instance-7"));

    @Test
    void buildsReadyConfirmation() throws IOException {
        ConfirmationPayloadFactory factory = new ConfirmationPayloadFactory(scope, FIXED_CLOCK);
        String json = factory.ready("swarm-start")
            .correlationId("corr-1")
            .idempotencyKey("idem-1")
            .state(new CommandState("Running", true, Map.of("tasks", 5)))
            .result("success")
            .details(Map.of("durationMs", 123))
            .build();

        assertMatchesFixture("/io/pockethive/controlplane/payload/ready-confirmation.json", json);
    }

    @Test
    void buildsErrorConfirmation() throws IOException {
        ConfirmationPayloadFactory factory = new ConfirmationPayloadFactory(scope, FIXED_CLOCK);
        String json = factory.error("swarm-stop")
            .correlationId("corr-2")
            .idempotencyKey("idem-2")
            .state(CommandState.status("Failed"))
            .phase("shutdown")
            .code("ERR-42")
            .message("Something went wrong")
            .retryable(Boolean.FALSE)
            .details(Map.of("stack", "trace"))
            .build();

        assertMatchesFixture("/io/pockethive/controlplane/payload/error-confirmation.json", json);
    }
}
