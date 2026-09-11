package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlPlaneInfoControllerTest {
    @Test
    void exposesConfiguredRabbitProjectionAndEnforcesReadAuthorization() throws Exception {
        var properties = new ControlPlaneProperties();
        properties.setExchange("tenant.control.events");
        var authorization = mock(OrchestratorEndpointAuthorization.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ControlPlaneInfoController(properties, authorization)).build();
        mvc.perform(get("/api/control-plane/info"))
            .andExpect(status().isOk())
            .andExpect(content().json("""
                {"subscriptionDestination":"/exchange/tenant.control.events/#",
                 "destinationPrefix":"/exchange/tenant.control.events/"}
                """, true));
        verify(authorization).requireReadPocketHive();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(authorization).requireReadPocketHive();
        mvc.perform(get("/api/control-plane/info")).andExpect(status().isForbidden());
    }
}
