package io.pockethive.worker.sdk.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerStateTest {

    @Test
    @SuppressWarnings({"unchecked", "resource"})
    void resolvesTopologyDefaultsUsingRuntimeQueues() throws Exception {
        String inboundPropertyKey = "PH_GEN_QUEUE";
        String outboundPropertyKey = "PH_MOD_QUEUE";
        String originalInboundProperty = System.getProperty(inboundPropertyKey);
        String originalOutboundProperty = System.getProperty(outboundPropertyKey);
        String runtimeGenQueue = "ph.runtime.gen";
        String runtimeModQueue = "ph.runtime.mod";

        System.setProperty(inboundPropertyKey, runtimeGenQueue);
        System.setProperty(outboundPropertyKey, runtimeModQueue);
        URL[] urls = new URL[] {
            Path.of("target", "classes").toAbsolutePath().toUri().toURL(),
            Path.of("..", "topology-core", "target", "classes").toAbsolutePath().toUri().toURL()
        };

        try (ChildFirstClassLoader loader = new ChildFirstClassLoader(urls, WorkerStateTest.class.getClassLoader())) {
            Class<?> topologyClass = Class.forName("io.pockethive.Topology", true, loader);
            Class<?> topologyDefaultsClass = Class.forName("io.pockethive.TopologyDefaults", true, loader);
            Class<?> workerDefinitionClass = Class.forName("io.pockethive.worker.sdk.runtime.WorkerDefinition", true, loader);
            Class<?> workerStateClass = Class.forName("io.pockethive.worker.sdk.runtime.WorkerState", true, loader);
            Class<?> workerTypeClass = Class.forName("io.pockethive.worker.sdk.config.WorkerType", true, loader);

            assertThat(topologyClass.getClassLoader()).isEqualTo(loader);
            Object workerTypeMessage = Enum.valueOf((Class) workerTypeClass, "MESSAGE");
            String defaultGenQueue = (String) topologyDefaultsClass.getField("GEN_QUEUE").get(null);
            String defaultModQueue = (String) topologyDefaultsClass.getField("MOD_QUEUE").get(null);
            String resolvedGenQueue = (String) topologyClass.getField("GEN_QUEUE").get(null);
            String resolvedModQueue = (String) topologyClass.getField("MOD_QUEUE").get(null);

            assertThat(resolvedGenQueue).isEqualTo(runtimeGenQueue);
            assertThat(resolvedModQueue).isEqualTo(runtimeModQueue);

            Constructor<?> definitionConstructor = workerDefinitionClass.getDeclaredConstructor(
                String.class,
                Class.class,
                workerTypeClass,
                String.class,
                String.class,
                String.class,
                Class.class
            );
            definitionConstructor.setAccessible(true);
            Object definition = definitionConstructor.newInstance(
                "testWorker",
                Object.class,
                workerTypeMessage,
                "test-role",
                defaultGenQueue,
                defaultModQueue,
                Void.class
            );

            Constructor<?> stateConstructor = workerStateClass.getDeclaredConstructor(workerDefinitionClass);
            stateConstructor.setAccessible(true);
            Object state = stateConstructor.newInstance(definition);

            Method inboundRoutesMethod = workerStateClass.getDeclaredMethod("inboundRoutes");
            Method outboundRoutesMethod = workerStateClass.getDeclaredMethod("outboundRoutes");
            inboundRoutesMethod.setAccessible(true);
            outboundRoutesMethod.setAccessible(true);

            Set<String> inboundRoutes = (Set<String>) inboundRoutesMethod.invoke(state);
            Set<String> outboundRoutes = (Set<String>) outboundRoutesMethod.invoke(state);

            assertThat(inboundRoutes)
                .containsExactly(resolvedGenQueue)
                .doesNotContain(defaultGenQueue);
            assertThat(outboundRoutes)
                .containsExactly(resolvedModQueue)
                .doesNotContain(defaultModQueue);
        } finally {
            restoreProperty(inboundPropertyKey, originalInboundProperty);
            restoreProperty(outboundPropertyKey, originalOutboundProperty);
        }
    }

    private static void restoreProperty(String key, String originalValue) {
        if (originalValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, originalValue);
        }
    }

    private static final class ChildFirstClassLoader extends URLClassLoader {

        ChildFirstClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("io.pockethive.")) {
                    try {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            loaded = findClass(name);
                        }
                        if (resolve) {
                            resolveClass(loaded);
                        }
                        return loaded;
                    } catch (ClassNotFoundException ex) {
                        // Fallback to parent
                    }
                }
                return super.loadClass(name, resolve);
            }
        }
    }
}
