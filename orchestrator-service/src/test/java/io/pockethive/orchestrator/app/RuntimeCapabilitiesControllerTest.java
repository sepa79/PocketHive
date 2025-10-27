package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.RuntimeCapabilitiesCatalogue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RuntimeCapabilitiesControllerTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private RuntimeCapabilitiesCatalogue catalogue;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        catalogue = new RuntimeCapabilitiesCatalogue();
        catalogue.update("sw1", mapper.readTree("{\"generator\":{\"1.0.0\":{\"manifest\":{\"capabilitiesVersion\":\"1.0.0\"},\"instances\":[\"gen-1\"]}}}"));
        RuntimeCapabilitiesController controller = new RuntimeCapabilitiesController(catalogue);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
    }

    @Test
    void returnsRuntimeCatalogue() throws Exception {
        MvcResult result = mvc.perform(get("/api/capabilities/runtime"))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(node.path("catalogue").path("sw1").path("generator").has("1.0.0")).isTrue();
    }
}
