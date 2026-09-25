package io.pockethive.scenarios;

import io.pockethive.auth.contract.PocketHivePermissionIds;
import io.pockethive.auth.contract.PocketHiveResourceTypes;
import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import io.pockethive.scenarios.auth.ScenarioManagerCurrentUserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ScenarioOperationsAccessControllerTest {
    @AfterEach void clearCaller() { ScenarioManagerCurrentUserHolder.clear(); }
    @ParameterizedTest
    @CsvSource({"bundles,true", "other,false"})
    void mapsCurrentCallerAndExistingUploadScopeWithoutCaching(String folder, boolean canUpload) throws Exception {
        ScenarioManagerCurrentUserHolder.set(AuthTestUsers.userWith(PocketHivePermissionIds.ALL, PocketHiveResourceTypes.FOLDER, folder));
        var access = new ScenarioOperationAccess(new ScenarioManagerAuthorization(),
            new ScenarioBundleOrganizationService(mock(ScenarioService.class)));
        MockMvcBuilders.standaloneSetup(new ScenarioOperationsAccessController(access)).build()
            .perform(get("/api/access/scenarios"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.canReload").value(false))
            .andExpect(jsonPath("$.canUpload").value(canUpload));
    }
}
