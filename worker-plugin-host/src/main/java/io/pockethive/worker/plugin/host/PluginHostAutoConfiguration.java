package io.pockethive.worker.plugin.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableConfigurationProperties(PluginHostProperties.class)
@Import(PluginConfigurationRegistrar.class)
public class PluginHostAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    PluginClasspathLoader pluginClasspathLoader(PluginHostProperties properties) {
        return new PluginClasspathLoader(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    PluginClasspathLoader.PluginHandle pluginHandle(PluginClasspathLoader loader) {
        return loader.loadPlugin();
    }

    @Bean
    @ConditionalOnMissingBean
    PluginDescriptor pluginDescriptor(PluginClasspathLoader.PluginHandle handle) {
        return handle.descriptor();
    }
}
