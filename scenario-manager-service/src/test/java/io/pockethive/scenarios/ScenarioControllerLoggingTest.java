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
        ScenarioBundleService bundles = Mockito.mock(ScenarioBundleService.class);
        ScenarioRuntimeMaterializer runtimeMaterializer = Mockito.mock(ScenarioRuntimeMaterializer.class);
        ScenarioVariablesService variables = Mockito.mock(ScenarioVariablesService.class);
        AvailableScenarioRegistry registry = Mockito.mock(AvailableScenarioRegistry.class);
        Mockito.when(registry.list()).thenReturn(Collections.emptyList());
        ScenarioController controller = new ScenarioController(service, bundles, runtimeMaterializer,
            variables, registry, new ScenarioManagerAuthorization(),
            new ScenarioAccessService(service, new ScenarioManagerAuthorization(), bundles));

        controller.list(false);

        assertThat(output).contains("[REST] GET /scenarios ->");
    }
}
