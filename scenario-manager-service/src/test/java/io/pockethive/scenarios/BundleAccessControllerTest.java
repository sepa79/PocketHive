package io.pockethive.scenarios;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BundleAccessControllerTest {
    @Test void exposesProjectionWithoutCaching() throws Exception {
        var projection = mock(BundleAccessProjection.class);
        when(projection.project(null)).thenReturn(new BundleAccessResponse(List.of(new BundleAccessView("team/a", true))));
        MockMvcBuilders.standaloneSetup(new BundleAccessController(projection)).build()
            .perform(get("/api/access/bundles"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.bundles[0].bundleKey").value("team/a"))
            .andExpect(jsonPath("$.bundles[0].canManage").value(true));
    }
}
