package io.pockethive.worker.plugin.host.fixture;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestWorkerPluginConfig {

    @Bean
    public String pluginSampleBean() {
        return "plugin-loaded";
    }
}
