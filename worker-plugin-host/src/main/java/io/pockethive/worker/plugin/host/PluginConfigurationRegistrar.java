package io.pockethive.worker.plugin.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class PluginConfigurationRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private static final String PROPERTIES_PREFIX = "pockethive.plugin-host";
    private static final Logger log = LoggerFactory.getLogger(PluginConfigurationRegistrar.class);

    private ConfigurableEnvironment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        if (environment == null) {
            throw new IllegalStateException("ConfigurableEnvironment is required to load plugin configuration");
        }
        PluginHostProperties properties = Binder.get(environment)
            .bindOrCreate(PROPERTIES_PREFIX, PluginHostProperties.class);
        PluginClasspathLoader loader = new PluginClasspathLoader(properties);
        PluginClasspathLoader.PluginHandle handle = loader.loadPlugin();
        new PluginConfigPropertySourceRegistrar(environment, properties).apply(handle);

        registerSingleton(registry, "pluginClasspathLoader", PluginClasspathLoader.class, loader);
        registerSingleton(registry, "pluginHandle", PluginClasspathLoader.PluginHandle.class, handle);

        registerPluginComponents(handle, registry);
    }

    private <T> void registerSingleton(BeanDefinitionRegistry registry, String beanName, Class<T> type, T instance) {
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        RootBeanDefinition beanDefinition = new RootBeanDefinition(type, () -> instance);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    private void registerPluginComponents(PluginClasspathLoader.PluginHandle handle, BeanDefinitionRegistry registry) {
        ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(registry, true, environment);
        DefaultResourceLoader pluginResourceLoader = new DefaultResourceLoader(handle.classLoader());
        ResourceLoader resourceLoader = new PathMatchingResourcePatternResolver(pluginResourceLoader);
        scanner.setResourceLoader(resourceLoader);
        scanner.addExcludeFilter(new AnnotationTypeFilter(SpringBootApplication.class));
        String basePackage = handle.configurationClass().getPackageName();
        Set<String> before = new HashSet<>(Arrays.asList(registry.getBeanDefinitionNames()));
        int registered = scanner.scan(basePackage);
        Set<String> after = new HashSet<>(Arrays.asList(registry.getBeanDefinitionNames()));
        after.removeAll(before);
        for (String beanName : after) {
            var definition = registry.getBeanDefinition(beanName);
            if (!(definition instanceof AbstractBeanDefinition abd)) {
                continue;
            }
            String className = abd.getBeanClassName();
            if ((className == null || className.isBlank()) && definition instanceof ScannedGenericBeanDefinition scanned) {
                className = scanned.getMetadata().getClassName();
            }
            if (className == null || className.isBlank()) {
                continue;
            }
            try {
                Class<?> beanClass = Class.forName(className, false, handle.classLoader());
                abd.setBeanClass(beanClass);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Failed to load plugin bean class " + className, e);
            }
        }
        log.info("Registered {} plugin bean definitions from package {}", registered, basePackage);
        if (registered == 0) {
            log.warn("Plugin package {} did not contribute any beans; worker host may fail to start", basePackage);
        }
    }

    @Override
    public void setEnvironment(org.springframework.core.env.Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable) {
            this.environment = configurable;
        } else {
            throw new IllegalArgumentException("Plugin host requires a ConfigurableEnvironment");
        }
    }
}
