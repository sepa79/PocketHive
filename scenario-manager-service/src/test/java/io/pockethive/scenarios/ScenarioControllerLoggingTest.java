package io.pockethive.scenarios;

import io.pockethive.scenarios.auth.ScenarioManagerAuthorization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ScenarioControllerLoggingTest {

    @Test
    void logsListingScenarios(CapturedOutput output) {
        ScenarioService service = Mockito.mock(ScenarioService.class);
        ScenarioBundleWorkspaceService workspace = Mockito.mock(ScenarioBundleWorkspaceService.class);
        ScenarioBundleOrganizationService organization = Mockito.mock(ScenarioBundleOrganizationService.class);
        ScenarioBundleSutService bundleSuts = Mockito.mock(ScenarioBundleSutService.class);
        ScenarioBundleContentService content = Mockito.mock(ScenarioBundleContentService.class);
        ScenarioRuntimeMaterializer runtimeMaterializer = Mockito.mock(ScenarioRuntimeMaterializer.class);
        ScenarioBundlePublicationService publication = Mockito.mock(ScenarioBundlePublicationService.class);
        ScenarioVariablesService variables = Mockito.mock(ScenarioVariablesService.class);
        AvailableScenarioRegistry registry = Mockito.mock(AvailableScenarioRegistry.class);
        Mockito.when(registry.list()).thenReturn(Collections.emptyList());
        ScenarioController controller = new ScenarioController(
            service,
            workspace,
            organization,
            bundleSuts,
            content,
            runtimeMaterializer,
            publication,
            variables,
            registry,
            new ScenarioManagerAuthorization());

        controller.list(false);

        assertThat(output).contains("[REST] GET /scenarios ->");
    }
}
