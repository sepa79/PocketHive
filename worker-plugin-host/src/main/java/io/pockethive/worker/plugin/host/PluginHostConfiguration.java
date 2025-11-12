package io.pockethive.worker.plugin.host;

import io.pockethive.worker.plugin.api.PocketHiveWorkerExtension;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import java.util.List;
import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

@Configuration
@EnableConfigurationProperties(PluginHostProperties.class)
@ConditionalOnProperty(prefix = "pockethive.plugin-host", name = "enabled", havingValue = "true", matchIfMissing = true)
class PluginHostConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PluginHostConfiguration.class);

    @Bean
    PocketHivePluginManager pocketHivePluginManager(PluginHostProperties properties) {
        return new PocketHivePluginManager(properties.getPluginsDir());
    }

    @Bean
    PocketHivePluginManifestValidator pocketHivePluginManifestValidator() {
        return new PocketHivePluginManifestValidator();
    }

    @Bean
    PluginManagerRunner pluginManagerRunner(PocketHivePluginManager pluginManager,
                                           PluginHostProperties properties,
                                           ApplicationContext hostContext,
                                           PocketHivePluginManifestValidator manifestValidator) {
        return new PluginManagerRunner(pluginManager, properties, hostContext, manifestValidator);
    }

    @Bean
    @DependsOn("pluginManagerRunner")
    @ConditionalOnProperty(prefix = "pockethive.plugin-host", name = "fail-on-missing-plugin", havingValue = "true", matchIfMissing = true)
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

    static final class PluginManagerRunner implements InitializingBean, DisposableBean {

        private final PocketHivePluginManager pluginManager;
        private final PluginHostProperties properties;
        private final ApplicationContext hostContext;
        private final PocketHivePluginManifestValidator manifestValidator;
        private AnnotationConfigApplicationContext pluginContext;
        private PocketHiveWorkerExtension activeExtension;
        private final List<ExportedBean> exportedBeans = new ArrayList<>();
        private String activePluginId;

        PluginManagerRunner(PocketHivePluginManager pluginManager,
                            PluginHostProperties properties,
                            ApplicationContext hostContext,
                            PocketHivePluginManifestValidator manifestValidator) {
            this.pluginManager = pluginManager;
            this.properties = properties;
            this.hostContext = hostContext;
            this.manifestValidator = manifestValidator;
        }

        @Override
        public void afterPropertiesSet() {
            pluginManager.loadPlugins();
            pluginManager.startPlugins();
            List<PocketHiveWorkerExtension> extensions = pluginManager.getExtensions(PocketHiveWorkerExtension.class);
            if (extensions.isEmpty()) {
                String message = "No PocketHive worker plugin detected in " + properties.getPluginsDir();
                if (properties.isFailOnMissingPlugin()) {
                    throw new IllegalStateException(message);
                }
                log.warn("{}", message);
                return;
            }
            if (extensions.size() > 1) {
                throw new IllegalStateException(
                    "Expected exactly one PocketHive worker plugin but found " + extensions.size());
            }
            PocketHiveWorkerExtension extension = extensions.getFirst();
            this.activeExtension = extension;
            String pluginId = pluginManager.whichPlugin(extension.getClass()).getPluginId();
            this.activePluginId = pluginId;
            PocketHivePluginDescriptor descriptor = manifestValidator.validate(pluginId, extension.getClass().getClassLoader());
            log.info("Starting PocketHive worker plugin role={}" , extension.role());
            pluginContext = new AnnotationConfigApplicationContext();
            pluginContext.setParent(hostContext);
            pluginContext.setClassLoader(extension.getClass().getClassLoader());
            for (Class<?> configClass : extension.configurationClasses()) {
                pluginContext.register(configClass);
            }
            pluginContext.refresh();
            exportWorkerBeans(pluginId);
            extension.onStart();
        }

        @Override
        public void destroy() {
            if (activeExtension != null) {
                try {
                    activeExtension.onStop();
                } catch (Exception ex) {
                    log.warn("Error while stopping plugin {}", activeExtension.role(), ex);
                }
            }
            removeExportedBeans();
            if (pluginContext != null) {
                pluginContext.close();
            }
            pluginManager.stopPlugins();
            pluginManager.unloadPlugins();
        }

        private void exportWorkerBeans(String pluginId) {
            var workers = pluginContext.getBeansWithAnnotation(PocketHiveWorker.class);
            if (workers.isEmpty()) {
                throw new IllegalStateException("Plugin " + pluginId + " does not define any @PocketHiveWorker beans");
            }
            if (!(hostContext instanceof org.springframework.context.ConfigurableApplicationContext configurableHost)) {
                throw new IllegalStateException("Host application context is not configurable");
            }
            var hostFactory = (DefaultListableBeanFactory) configurableHost.getBeanFactory();
            workers.forEach((beanName, bean) -> {
                String exportedName = pluginId + ":" + beanName;
                hostFactory.registerSingleton(exportedName, bean);
                exportedBeans.add(new ExportedBean(exportedName, bean));
                log.info("Registered PocketHive worker bean {} from plugin {}", exportedName, pluginId);
            });
        }

        private void removeExportedBeans() {
            if (!(hostContext instanceof org.springframework.context.ConfigurableApplicationContext configurableHost)) {
                return;
            }
            var hostFactory = (DefaultListableBeanFactory) configurableHost.getBeanFactory();
            for (ExportedBean exported : exportedBeans) {
                if (hostFactory.containsSingleton(exported.name())) {
                    hostFactory.destroySingleton(exported.name());
                    log.info("Deregistered PocketHive worker bean {}", exported.name());
                }
            }
            exportedBeans.clear();
        }

        private record ExportedBean(String name, Object bean) { }
    }
}
