package io.pockethive.orchestrator;

import io.pockethive.Topology;
import io.pockethive.util.BeeNameGenerator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

public final class OrchestratorControlPlaneInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

    private static final String ROLE = "orchestrator";
    private static final String BEE_NAME_PROPERTY = "bee.name";
    private static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.manager.instance-id";
    private static final String MANAGER_SWARM_ID_PROPERTY = "pockethive.control-plane.manager.swarm-id";
    private static final String SWARM_ID_PROPERTY = "pockethive.control-plane.swarm-id";
    private static final String PROPERTY_SOURCE_NAME = "orchestratorControlPlaneDefaults";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        String configured = normalise(environment.getProperty(INSTANCE_ID_PROPERTY));
        if (configured != null) {
            System.setProperty(BEE_NAME_PROPERTY, configured);
            System.setProperty(INSTANCE_ID_PROPERTY, configured);
            return;
        }

        String existing = normalise(System.getProperty(BEE_NAME_PROPERTY));
        String swarmId = resolveSwarmId(environment);
        String resolved = existing != null ? existing : BeeNameGenerator.generate(ROLE, swarmId);

        System.setProperty(BEE_NAME_PROPERTY, resolved);
        System.setProperty(INSTANCE_ID_PROPERTY, resolved);
        injectInstanceId(environment, resolved);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static void injectInstanceId(ConfigurableEnvironment environment, String resolved) {
        MutablePropertySources propertySources = environment.getPropertySources();
        Map<String, Object> values = new HashMap<>();
        values.put(INSTANCE_ID_PROPERTY, resolved);
        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, values);
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.replace(PROPERTY_SOURCE_NAME, propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }
    }

    private static String resolveSwarmId(Environment environment) {
        String managerOverride = normalise(environment.getProperty(MANAGER_SWARM_ID_PROPERTY));
        if (managerOverride != null) {
            return managerOverride;
        }
        String global = normalise(environment.getProperty(SWARM_ID_PROPERTY));
        if (global != null) {
            return global;
        }
        return Topology.SWARM_ID;
    }

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
