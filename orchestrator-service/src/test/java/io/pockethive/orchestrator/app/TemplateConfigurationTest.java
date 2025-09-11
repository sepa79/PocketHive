package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.SwarmTemplate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateConfigurationTest {
    @Test
    void loadsTemplateFromYaml() throws Exception {
        TemplateConfiguration cfg = new TemplateConfiguration();
        SwarmTemplate template = cfg.swarmTemplate();
        assertEquals("pockethive-swarm-controller:latest", template.getImage());
        assertEquals(4, template.getBees().size());
        assertEquals("generator", template.getBees().get(0).role());
        assertEquals("postprocessor", template.getBees().get(3).role());
    }
}
