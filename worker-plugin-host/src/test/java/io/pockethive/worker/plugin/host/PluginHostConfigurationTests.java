package io.pockethive.worker.plugin.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

class PluginHostConfigurationTests {

    @Test
    void pluginHostCanBeDisabled() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "pockethive.plugin-host.enabled=false");
            context.register(PluginHostConfiguration.class);
            context.refresh();
            assertThat(context.containsBean("pocketHivePluginManager")).isFalse();
        }
    }
}
