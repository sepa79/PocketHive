package io.pockethive.worker.plugin.host;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TestPluginConfiguration {

    @Bean
    public String pluginSampleBean() {
        return "plugin-loaded";
    }
}
