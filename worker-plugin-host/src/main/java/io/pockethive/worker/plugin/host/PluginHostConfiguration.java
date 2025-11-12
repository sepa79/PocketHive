package io.pockethive.worker.plugin.host;

import io.pockethive.worker.plugin.api.PocketHiveWorkerExtension;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

@Configuration
@EnableConfigurationProperties(PluginHostProperties.class)
@ConditionalOnProperty(prefix = "pockethive.plugin-host", name = "enabled", havingValue = "true", matchIfMissing = true)
class PluginHostConfiguration {

    @Bean
    PocketHivePluginManager pocketHivePluginManager(PluginHostProperties properties) {
        return new PocketHivePluginManager(properties.getPluginsDir());
    }

    @Bean
    PocketHivePluginManifestValidator pocketHivePluginManifestValidator() {
        return new PocketHivePluginManifestValidator();
    }

    @Bean
    static PluginLifecycle pluginLifecycle(PocketHivePluginManager pluginManager,
                                    PluginHostProperties properties,
                                    ApplicationContext hostContext,
                                    PocketHivePluginManifestValidator manifestValidator) {
        return new PluginLifecycle(pluginManager, properties, hostContext, manifestValidator);
    }

    @Bean
    @DependsOn("pluginLifecycle")
    PocketHiveWorkerExtension pocketHiveWorkerExtension(PocketHivePluginManager pluginManager) {
        List<PocketHiveWorkerExtension> extensions = pluginManager.getExtensions(PocketHiveWorkerExtension.class);
        if (extensions.isEmpty()) {
            throw new IllegalStateException("No PocketHive worker plugin was discovered");
        }
        if (extensions.size() > 1) {
            throw new IllegalStateException("Multiple PocketHive worker plugins discovered: " + extensions.size());
        }
        return extensions.getFirst();
    }

    static final class PluginLifecycle implements BeanFactoryPostProcessor, DisposableBean {

        private static final Logger log = LoggerFactory.getLogger(PluginLifecycle.class);

        private final PocketHivePluginManager pluginManager;
        private final PluginHostProperties properties;
        private final ApplicationContext hostContext;
        private final PocketHivePluginManifestValidator manifestValidator;
        private AnnotationConfigApplicationContext pluginContext;
        private final List<String> exportedBeans = new ArrayList<>();
        private DefaultListableBeanFactory hostBeanFactory;

        PluginLifecycle(PocketHivePluginManager pluginManager,
                        PluginHostProperties properties,
                        ApplicationContext hostContext,
                        PocketHivePluginManifestValidator manifestValidator) {
            this.pluginManager = pluginManager;
            this.properties = properties;
            this.hostContext = hostContext;
            this.manifestValidator = manifestValidator;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            if (beanFactory instanceof DefaultListableBeanFactory defaultFactory) {
                this.hostBeanFactory = defaultFactory;
            } else {
                throw new IllegalStateException("Host bean factory must be DefaultListableBeanFactory");
            }
            pluginManager.loadPlugins();
            pluginManager.startPlugins();
            List<PocketHiveWorkerExtension> extensions = pluginManager.getExtensions(PocketHiveWorkerExtension.class);
            if (extensions.isEmpty()) {
                String message = "No PocketHive worker plugin detected in " + properties.getPluginsDir();
                if (properties.isFailOnMissingPlugin()) {
                    throw new IllegalStateException(message);
                }
                log.warn(message);
                return;
            }
            if (extensions.size() > 1) {
                throw new IllegalStateException(
                    "Expected exactly one PocketHive worker plugin but found " + extensions.size());
            }
            PocketHiveWorkerExtension extension = extensions.getFirst();
            String pluginId = pluginManager.whichPlugin(extension.getClass()).getPluginId();
            PocketHivePluginDescriptor descriptor = manifestValidator.validate(pluginId, extension.getClass().getClassLoader());
            log.info("Starting PocketHive worker plugin role={}", extension.role());
            pluginContext = new AnnotationConfigApplicationContext();
            pluginContext.setParent(hostContext);
            pluginContext.setClassLoader(extension.getClass().getClassLoader());
            for (Class<?> configClass : extension.configurationClasses()) {
                pluginContext.register(configClass);
            }
            pluginContext.refresh();
            registerWorkerBeans(beanFactory, pluginId);
            extension.onStart();
        }

        private void registerWorkerBeans(ConfigurableListableBeanFactory hostFactory, String pluginId) {
            var workers = pluginContext.getBeansWithAnnotation(PocketHiveWorker.class);
            if (workers.isEmpty()) {
                throw new IllegalStateException("Plugin " + pluginId + " does not define any @PocketHiveWorker beans");
            }
            workers.forEach((beanName, bean) -> {
                String exportedName = pluginId + ":" + beanName;
                hostFactory.registerSingleton(exportedName, bean);
                exportedBeans.add(exportedName);
                log.info("Registered PocketHive worker bean {} from plugin {}", exportedName, pluginId);
            });
        }

        @Override
        public void destroy() {
            if (hostBeanFactory != null) {
                exportedBeans.forEach(name -> {
                    if (hostBeanFactory.containsSingleton(name)) {
                        hostBeanFactory.destroySingleton(name);
                    }
                });
            }
            exportedBeans.clear();
            if (pluginContext != null) {
                pluginContext.close();
            }
            pluginManager.stopPlugins();
            pluginManager.unloadPlugins();
        }
    }
}
