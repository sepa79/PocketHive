package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.pockethive.orchestrator.domain.*;
import io.pockethive.swarm.model.NetworkMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class SwarmTemplateScopeResolverTest {
    private final ScenarioClient scenarios = mock(ScenarioClient.class);
    private final SwarmTemplateScopeResolver resolver = new SwarmTemplateScopeResolver(scenarios);
    @Test void enrichesOnceAndPreservesExistingTemplateDetails() throws Exception {
        Swarm swarm = new Swarm("one", "controller", "container", "run", NetworkMode.DIRECT);
        swarm.attachTemplate(new SwarmTemplateMetadata("template", "image", List.of()));
        when(scenarios.fetchScenarioTemplate("template")).thenReturn(
            new ScenarioClient.ScenarioTemplateDescriptor("template", "key", "folder/bundle", "folder", false));
        var resolved = resolver.resolve(swarm);
        assertThat(resolved.bundlePath()).isEqualTo("folder/bundle");
        assertThat(resolved.controllerImage()).isEqualTo("image");
        assertThat(swarm.templateMetadata()).isEqualTo(resolved);
        assertThat(resolver.resolve(swarm)).isEqualTo(resolved);
        verify(scenarios, times(1)).fetchScenarioTemplate("template");
    }
    @Test void missingDescriptorFailsInsteadOfGuessingScope() {
        assertThatThrownBy(() -> resolver.fetchScenarioTemplate("missing"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to fetch template metadata");
    }
}
