package io.pockethive.orchestrator.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.pockethive.orchestrator.auth.OrchestratorAuthorization;
import io.pockethive.orchestrator.domain.SwarmStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.swarm.model.NetworkMode;
import org.springframework.http.ResponseEntity;

class SwarmAccessControllerTest {
    @Test void exposesAnEmptyCollectionWithNoStore() throws Exception {
        var access = new SwarmAccessService(new OrchestratorAuthorization(),
            new SwarmTemplateScopeResolver(mock(ScenarioClient.class)));
        var controller = new SwarmAccessController(new SwarmAccessProjection(new SwarmStore(), access));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/access/swarms"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.swarms").isEmpty());
    }
    @Test void projectionDoesNotShadowTheSwarmNamedAccess() throws Exception {
        var store = new SwarmStore();
        store.register(new Swarm("access", "controller", "container", "run", NetworkMode.DIRECT));
        var access = new SwarmAccessService(new OrchestratorAuthorization(),
            new SwarmTemplateScopeResolver(mock(ScenarioClient.class)));
        var projection = new SwarmAccessController(new SwarmAccessProjection(store, access));
        var lifecycle = mock(SwarmController.class);
        when(lifecycle.view("access")).thenReturn(ResponseEntity.ok().build());
        var mvc = MockMvcBuilders.standaloneSetup(lifecycle, projection).build();
        mvc.perform(get("/api/access/swarms"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.swarms[0].swarmId").value("access"));
        mvc.perform(get("/api/swarms/access"))
            .andExpect(status().isOk()).andExpect(handler().handlerType(SwarmController.class));
        verify(lifecycle).view("access");
    }
}
