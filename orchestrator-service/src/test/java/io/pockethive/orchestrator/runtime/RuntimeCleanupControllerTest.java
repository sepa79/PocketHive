package io.pockethive.orchestrator.runtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.JacksonConfiguration;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Candidate;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RuntimeCleanupControllerTest {
    @Mock
    RuntimeReconciliationService service;

    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void planDelegatesRequestToRuntimeReconciliationService() throws Exception {
        Plan response = new Plan(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            true,
            true,
            "hash-1",
            "high",
            List.of(new Candidate(
                "lifecycle:swarm:sw1",
                RuntimeCleanupAction.LIFECYCLE_REMOVE_SWARM,
                "sw1",
                "swarm",
                "manager",
                "swarm-controller",
                "controller-1",
                "READY",
                "controller:test",
                null,
                null,
                true,
                true,
                "registered swarm lifecycle cleanup",
                Map.of())),
            List.of());
        when(service.plan(any(PlanRequest.class))).thenReturn(response);

        mvc().perform(post("/api/runtime/cleanup/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
	                      "swarmId": "sw1",
	                      "runId": "run-1",
	                      "includeRunning": true,
	                      "includeRabbit": true
	                    }
	                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.candidateSetHash").value("hash-1"))
            .andExpect(jsonPath("$.candidates[0].candidateId").value("lifecycle:swarm:sw1"))
            .andExpect(jsonPath("$.candidates[0].action").value("LIFECYCLE_REMOVE_SWARM"));

        ArgumentCaptor<PlanRequest> captor = ArgumentCaptor.forClass(PlanRequest.class);
        verify(service).plan(captor.capture());
        PlanRequest request = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(request.swarmId()).isEqualTo("sw1");
        org.assertj.core.api.Assertions.assertThat(request.runId()).isEqualTo("run-1");
        org.assertj.core.api.Assertions.assertThat(request.includeRunning()).isTrue();
        org.assertj.core.api.Assertions.assertThat(request.includeRabbit()).isTrue();
    }

    @Test
    void cleanupExceptionReturnsStatusAndMessage() throws Exception {
        when(service.plan(any(PlanRequest.class)))
            .thenThrow(new RuntimeCleanupException(HttpStatus.CONFLICT, "candidateSetHash does not match the current cleanup plan"));

        mvc().perform(post("/api/runtime/cleanup/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"swarmId\":\"sw1\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value("candidateSetHash does not match the current cleanup plan"));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new RuntimeCleanupController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }
}
