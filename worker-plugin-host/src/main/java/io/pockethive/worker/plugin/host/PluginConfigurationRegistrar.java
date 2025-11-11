package io.pockethive.worker.plugin.host;

import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;

class PluginConfigurationRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private static final String PROPERTIES_PREFIX = "pockethive.plugin-host";

    private Environment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        PluginHostProperties properties = Binder.get(environment)
            .bindOrCreate(PROPERTIES_PREFIX, PluginHostProperties.class);
        PluginClasspathLoader loader = new PluginClasspathLoader(properties);
        PluginClasspathLoader.PluginHandle handle = loader.loadPlugin();

        registerSingleton(registry, "pluginClasspathLoader", PluginClasspathLoader.class, loader);
        registerSingleton(registry, "pluginHandle", PluginClasspathLoader.PluginHandle.class, handle);

        AnnotatedGenericBeanDefinition definition = new AnnotatedGenericBeanDefinition(handle.configurationClass());
        String beanName = BeanDefinitionReaderUtils.generateBeanName(definition, registry);
        BeanDefinitionReaderUtils.registerBeanDefinition(new BeanDefinitionHolder(definition, beanName), registry);
    }

    private <T> void registerSingleton(BeanDefinitionRegistry registry, String beanName, Class<T> type, T instance) {
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        RootBeanDefinition beanDefinition = new RootBeanDefinition(type, () -> instance);
        registry.registerBeanDefinition(beanName, beanDefinition);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }
}
