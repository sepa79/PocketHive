package io.pockethive.orchestrator.planner;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.util.Optional;
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
    void missingExactAndCurrentJournalRunsReturnNotFound() throws Exception {
        var authorization = mock(OrchestratorEndpointAuthorization.class);
        var planner = mock(PocketPlannerClient.class);
        var metadata = mock(JournalRunMetadata.class);
        var queries = mock(JournalRunQueries.class);
        var store = mock(SwarmStore.class);
        var swarm = mock(Swarm.class);
        when(store.find("swarm")).thenReturn(Optional.of(swarm));
        when(swarm.getRunId()).thenReturn("registered-run");
        var runs = new PlannerRunService(planner, metadata, queries, store, "postgres");
        var mvc = MockMvcBuilders.standaloneSetup(new PlannerController(authorization, planner, runs)).build();

        mvc.perform(get("/api/planner/runs/swarm/absent-run"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Exact journal run not found"));
        mvc.perform(get("/api/planner/swarms/swarm"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.message").value("Exact journal run not found"));

        verify(authorization).requireReadDeployment();
        verify(authorization).requireReadSwarm("swarm");
        verify(queries).metadataSummary("swarm", "absent-run");
        verify(queries).metadataSummary("swarm", "registered-run");
        verifyNoMoreInteractions(queries);
        verifyNoInteractions(planner, metadata);
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
