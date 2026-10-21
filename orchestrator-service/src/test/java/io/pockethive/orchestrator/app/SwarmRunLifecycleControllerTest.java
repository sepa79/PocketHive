package io.pockethive.orchestrator.app;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.contract.*;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.auth.OrchestratorAuthFilter;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.domain.*;
import io.pockethive.swarm.model.NetworkMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SwarmRunLifecycleControllerTest {
    private MockMvc mvc;
    private AuthServiceClient auth;
    private SwarmAccessService access;
    private ControlPlanePublisher publisher;
    private SwarmStore store;

    @BeforeEach void setup() {
        store = new SwarmStore();
        var swarm = new Swarm("alpha", "controller-1", "container-1", "run-1", NetworkMode.DIRECT);
        swarm.attachTemplate(new SwarmTemplateMetadata("template-1", "controller:latest", List.of()));
        store.register(swarm);
        var coordinator = new SwarmOperationCoordinator();
        var properties = new ControlPlaneProperties();
        properties.setInstanceId("orchestrator-1");
        publisher = mock(ControlPlanePublisher.class);
        access = mock(SwarmAccessService.class);
        when(access.canRead(any(), any())).thenReturn(true);
        when(access.canRun(any(), any())).thenReturn(true);
        when(access.canManage(any(), any())).thenReturn(true);
        var commands = new SwarmLifecycleCommandService(store,
            new OperationDispatchService(coordinator, mock(OperationOutcomePublisher.class), store),
            publisher, mock(FilesystemSwarmRemoveStore.class), mock(HiveJournal.class), properties);
        auth = mock(AuthServiceClient.class);
        user(PocketHivePermissionIds.ALL);
        mvc = MockMvcBuilders.standaloneSetup(new SwarmRunLifecycleController(store, access, commands,
            new ControlResponseFactory(properties), coordinator))
            .addFilters(new OrchestratorAuthFilter(auth, new OrchestratorAuthorization())).build();
    }

    private void user(String permission) {
        when(auth.resolve(anyString())).thenReturn(new AuthenticatedUserDto(UUID.randomUUID(), "operator", "Operator", true,
            AuthProvider.DEV, List.of(new AuthGrantDto(AuthProduct.POCKETHIVE, permission, PocketHiveResourceTypes.DEPLOYMENT, "*"))));
    }

    @Test void exactRunStartAllowsRunButStopRequiresAll() throws Exception {
        user(PocketHivePermissionIds.RUN);
        mvc.perform(post("/api/swarms/alpha/runs/run-1/start").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"same-key\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.correlationId").isString());
        mvc.perform(post("/api/swarms/alpha/runs/run-1/stop").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"stop-key\"}"))
            .andExpect(status().isForbidden());
        verify(publisher, times(1)).publishSignal(any());
    }

    @Test void viewerAnonymousAndOutOfScopeCommandsCannotPublish() throws Exception {
        mvc.perform(post("/api/swarms/alpha/runs/run-1/start").contentType(MediaType.APPLICATION_JSON)
            .content("{\"idempotencyKey\":\"same-key\"}")).andExpect(status().isUnauthorized());
        user(PocketHivePermissionIds.VIEW);
        mvc.perform(post("/api/swarms/alpha/runs/run-1/start").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"same-key\"}"))
            .andExpect(status().isForbidden());
        user(PocketHivePermissionIds.ALL);
        when(access.canRun(any(), any())).thenReturn(false);
        mvc.perform(post("/api/swarms/alpha/runs/run-1/start").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"same-key\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(publisher);
    }

    @Test void mismatchedRunReturnsConflictWithoutDispatch() throws Exception {
        mvc.perform(post("/api/swarms/alpha/runs/old-run/start").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"same-key\"}"))
            .andExpect(status().isConflict());
        verifyNoInteractions(publisher);
    }

    @Test void operationReadsRequireScopeAndExactRun() throws Exception {
        var result = mvc.perform(post("/api/swarms/alpha/runs/run-1/stop").header("Authorization", "Bearer caller")
            .contentType(MediaType.APPLICATION_JSON).content("{\"idempotencyKey\":\"same-key\"}"))
            .andExpect(status().isAccepted()).andReturn();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        String correlation = json.path("correlationId").asText();
        mvc.perform(get("/api/swarms/alpha/runs/run-1/operations/" + correlation).header("Authorization", "Bearer caller"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.runtime.runId").value("run-1"));
        mvc.perform(get("/api/swarms/alpha/runs/other/operations/" + correlation).header("Authorization", "Bearer caller"))
            .andExpect(status().isNotFound());
        when(access.canRead(any(), any())).thenReturn(false);
        mvc.perform(get("/api/swarms/alpha/runs/run-1/operations/" + correlation).header("Authorization", "Bearer caller"))
            .andExpect(status().isForbidden());
        when(access.canRead(any(), any())).thenReturn(true);
        store.register(new Swarm("alpha", "controller-2", "container-2", "run-2", NetworkMode.DIRECT));
        mvc.perform(get("/api/swarms/alpha/runs/run-1/operations/" + correlation).header("Authorization", "Bearer caller"))
            .andExpect(status().isNotFound());
    }
}
