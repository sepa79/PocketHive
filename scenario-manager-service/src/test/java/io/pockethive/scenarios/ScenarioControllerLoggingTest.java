package io.pockethive.scenarios;

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
        Mockito.when(service.list()).thenReturn(Collections.emptyList());
        ScenarioController controller = new ScenarioController(service);

        controller.list();

        assertThat(output).contains("[REST] GET /scenarios ->");
    }
}
