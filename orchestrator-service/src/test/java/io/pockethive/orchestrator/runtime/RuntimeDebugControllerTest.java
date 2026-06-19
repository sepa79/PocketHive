package io.pockethive.orchestrator.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.JacksonConfiguration;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologyRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeLogsResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeTarget;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.SourceSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RuntimeDebugControllerTest {
    @Mock
    RuntimeDebugService service;
    @Mock
    RuntimeReconciliationService reconciliationService;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void capabilitiesExposeScopedRuntimeDebugContract() throws Exception {
        mvc().perform(get("/api/runtime/debug/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeDebugContractVersion").value("2"))
            .andExpect(jsonPath("$.cleanupContractVersion").value("2"))
            .andExpect(jsonPath("$.runtimeDebugReadsBackedByOrchestrator").value(true))
            .andExpect(jsonPath("$.cleanupPlanHasExecutionRisk").value(true))
            .andExpect(jsonPath("$.cleanupPlanUsesApprovalFields").value(false))
            .andExpect(jsonPath("$.cleanupExecuteRequiresCandidateSetHash").value(true))
            .andExpect(jsonPath("$.rabbitTopologyExactByDefault").value(true))
            .andExpect(jsonPath("$.cleanupSupportsRegisteredStateOverride").value(true));
    }

    @Test
    void listResourcesDelegatesToRuntimeDebugService() throws Exception {
        when(service.list(any(ResourceListRequest.class))).thenReturn(new ResourceListResponse(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            new RuntimeDebugContracts.Counts(0, 0, 0),
            List.of(),
            List.of(),
            List.of()));

        mvc().perform(post("/api/runtime/debug/resources/list")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "computeAdapter": "DOCKER_SINGLE",
                      "swarmId": "sw1",
                      "runId": "run-1",
                      "includeManagers": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.computeAdapter").value("DOCKER_SINGLE"))
            .andExpect(jsonPath("$.swarmId").value("sw1"));

        ArgumentCaptor<ResourceListRequest> captor = ArgumentCaptor.forClass(ResourceListRequest.class);
        verify(service).list(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().includeManagers()).isTrue();
    }

    @Test
    void logsDelegatesToRuntimeDebugService() throws Exception {
        RuntimeTarget target = new RuntimeTarget(
            "runtime-1",
            "container",
            "controller",
            "manager",
            "sw1",
            "run-1",
            "swarm-controller",
            "controller-1",
            "controller",
            "running",
            "controller:test",
            Map.of());
        when(service.logs(any(RuntimeLogsRequest.class))).thenReturn(new RuntimeLogsResponse(
            target,
            20,
            null,
            true,
            1,
            "hello"));

        mvc().perform(post("/api/runtime/debug/resources/logs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "computeAdapter": "DOCKER_SINGLE",
                      "swarmId": "sw1",
                      "resourceKind": "manager",
                      "instance": "controller-1",
                      "tailLines": 20
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target.resourceKind").value("manager"))
            .andExpect(jsonPath("$.logs").value("hello"));

        verify(service).logs(any(RuntimeLogsRequest.class));
    }

    @Test
    void rabbitTopologyDelegatesToOrchestratorReconciliationService() throws Exception {
        when(reconciliationService.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(new RabbitTopologySnapshot(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            SourceSummary.present(),
            SourceSummary.present(),
            true,
            List.of(new RuntimeDebugContracts.RabbitQueueSnapshot(
                "ph.control.sw1.processor.worker-1",
                true,
                0L,
                1,
                null,
                null,
                null,
                false,
                null)),
            List.of(),
            List.of()));

        mvc().perform(post("/api/runtime/debug/rabbit/topology")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "computeAdapter": "DOCKER_SINGLE",
                      "swarmId": "sw1",
                      "runId": "run-1"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exactOnly").value(true))
            .andExpect(jsonPath("$.queues[0].name").value("ph.control.sw1.processor.worker-1"))
            .andExpect(jsonPath("$.queues[0].consumers").value(1));

        ArgumentCaptor<RabbitTopologyRequest> captor = ArgumentCaptor.forClass(RabbitTopologyRequest.class);
        verify(reconciliationService).rabbitTopology(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().computeAdapter()).isEqualTo("DOCKER_SINGLE");
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new RuntimeDebugController(service, reconciliationService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }
}
