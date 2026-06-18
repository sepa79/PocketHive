package io.pockethive.orchestrator.runtime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.app.JacksonConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeDebugControllerTest {
    private final ObjectMapper mapper = new JacksonConfiguration().objectMapper();

    @Test
    void capabilitiesExposeScopedRuntimeDebugContract() throws Exception {
        mvc().perform(get("/api/runtime/debug/capabilities"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runtimeDebugContractVersion").value("1"))
            .andExpect(jsonPath("$.cleanupContractVersion").value("1"))
            .andExpect(jsonPath("$.cleanupPlanHasExecutionRisk").value(true))
            .andExpect(jsonPath("$.cleanupPlanUsesApprovalFields").value(false))
            .andExpect(jsonPath("$.cleanupExecuteRequiresCandidateSetHash").value(true))
            .andExpect(jsonPath("$.rabbitTopologyExactByDefault").value(true));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new RuntimeDebugController())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }
}
