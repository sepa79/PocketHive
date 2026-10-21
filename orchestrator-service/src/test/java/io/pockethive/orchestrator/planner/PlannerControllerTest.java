package io.pockethive.orchestrator.planner;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PlannerControllerTest {
    @Test
    void httpBoundaryValidatesSelectionsAndAuthorizesBeforeCallingOwners() throws Exception {
        var authorization = mock(OrchestratorEndpointAuthorization.class);
        var client = mock(PocketPlannerClient.class);
        var runs = mock(PlannerRunService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PlannerController(authorization, client, runs)).build();
        mvc.perform(post("/api/planner/runs/swarm/run").contentType("application/json")
            .content("{\"instanceId\":\"qa\",\"revision\":0,\"planId\":\"\",\"streamId\":\"s\",\"activityId\":\"a\"}"))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
        mvc.perform(post("/api/planner/validate").contentType("application/json")
            .content("{\"instanceId\":\"qa\",\"revision\":0,\"planId\":\"p\",\"streamId\":\"s\",\"activityId\":\"a\",\"tag\":\"forged\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/planner/validate").contentType("application/json")
            .content("{\"instanceId\":\"qa\",\"planId\":\"p\",\"streamId\":\"s\",\"activityId\":\"a\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/planner/validate").contentType("application/json")
            .content("{\"instanceId\":\"qa\",\"revision\":null,\"planId\":\"p\",\"streamId\":\"s\",\"activityId\":\"a\"}"))
            .andExpect(status().isBadRequest());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "No management permission"))
            .when(authorization).requireManageDeployment();
        mvc.perform(post("/api/planner/runs/swarm/run").contentType("application/json")
            .content("{\"instanceId\":\"qa\",\"revision\":0,\"planId\":\"p\",\"streamId\":\"s\",\"activityId\":\"a\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value("No management permission"));
        verifyNoInteractions(runs, client);
    }

    @Test
    void exactHistoricalIdentityIsPassedUnchanged() throws Exception {
        var authorization = mock(OrchestratorEndpointAuthorization.class);
        var runs = mock(PlannerRunService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PlannerController(authorization, mock(PocketPlannerClient.class), runs)).build();
        mvc.perform(get("/api/planner/runs/reused-name/old-run")).andExpect(status().isOk());
        verify(authorization).requireReadDeployment();
        verify(runs).get("reused-name", "old-run");
    }
}
