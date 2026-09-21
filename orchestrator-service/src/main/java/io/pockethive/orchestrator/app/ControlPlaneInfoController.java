package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.api.RabbitStompSubscription;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Responsibility: expose Rabbit's Control Plane subscription projection to authorized UI clients.
 * Must not: construct broker addresses, expose credentials or operate broker resources.
 * Contract: RESP-CONTROL-STOMP-INFO — docs/architecture/runtime-responsibilities.md#resp-control-stomp-info.
 */
@RestController
public final class ControlPlaneInfoController {
    private final RabbitStompSubscription subscription;
    private final OrchestratorEndpointAuthorization authorization;

    public ControlPlaneInfoController(ControlPlaneProperties properties,
                                      OrchestratorEndpointAuthorization authorization) {
        this.subscription = RabbitResourceNames.controlStompSubscription(properties.getExchange());
        this.authorization = java.util.Objects.requireNonNull(authorization, "authorization");
    }

    @GetMapping("/api/control-plane/info")
    public RabbitStompSubscription info() {
        authorization.requireReadPocketHive();
        return subscription;
    }
}
