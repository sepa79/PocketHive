package io.pockethive.orchestrator.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.auth.OrchestratorEndpointAuthorization;
import io.pockethive.orchestrator.infra.schema.ControlPlaneSchemaBundle;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlPlaneSchemaControllerTest {
    @Test
    void servesCompleteCanonicalDocumentAndAuthorizesCacheHits() throws Exception {
        var mapper = new ObjectMapper();
        var bundle = new ControlPlaneSchemaBundle(mapper);
        var authorization = mock(OrchestratorEndpointAuthorization.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ControlPlaneSchemaController(bundle, authorization)).build();
        var result = mvc.perform(get("/api/control-plane/schema/control-events"))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", bundle.etag()))
            .andExpect(header().string("Cache-Control", "max-age=300"))
            .andReturn();
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(bundle.bytes());
        mvc.perform(get("/api/control-plane/schema/control-events").header("If-None-Match", bundle.etag()))
            .andExpect(status().isNotModified()).andExpect(content().string(""));
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(authorization).requireReadPocketHive();
        mvc.perform(get("/api/control-plane/schema/control-events").header("If-None-Match", bundle.etag()))
            .andExpect(status().isForbidden());
    }
}
