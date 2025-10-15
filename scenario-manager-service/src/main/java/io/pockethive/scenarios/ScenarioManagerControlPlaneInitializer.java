package io.pockethive.scenarios;

import io.pockethive.Topology;
import io.pockethive.util.BeeNameGenerator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

public final class ScenarioManagerControlPlaneInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

  private static final String ROLE = "scenario-manager";
  private static final String PROPERTY_SOURCE_NAME = "scenarioManagerControlPlaneDefaults";
  private static final String APPLICATION_NAME_PROPERTY = "spring.application.name";
  private static final String ROLE_PROPERTY = "pockethive.control-plane.manager.role";
  private static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.manager.instance-id";
  private static final String MANAGER_SWARM_ID_PROPERTY = "pockethive.control-plane.manager.swarm-id";
  private static final String GLOBAL_SWARM_ID_PROPERTY = "pockethive.control-plane.swarm-id";
  private static final String BEE_NAME_PROPERTY = "bee.name";

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    ConfigurableEnvironment environment = applicationContext.getEnvironment();
    Map<String, Object> defaults = new HashMap<>();

    ensureApplicationName(environment, defaults);
    ensureRole(environment, defaults);
    String swarmId = resolveSwarmId(environment, defaults);
    ensureInstance(environment, defaults, swarmId);

    if (defaults.isEmpty()) {
      return;
    }

    MutablePropertySources propertySources = environment.getPropertySources();
    MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, defaults);
    if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
      propertySources.replace(PROPERTY_SOURCE_NAME, propertySource);
    } else {
      propertySources.addFirst(propertySource);
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE;
  }

  private static void ensureApplicationName(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    if (isBlank(environment.getProperty(APPLICATION_NAME_PROPERTY))) {
      defaults.put(APPLICATION_NAME_PROPERTY, ROLE);
    }
  }

  private static void ensureRole(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    if (isBlank(environment.getProperty(ROLE_PROPERTY))) {
      defaults.put(ROLE_PROPERTY, ROLE);
    }
  }

  private static String resolveSwarmId(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    String managerOverride = normalise(environment.getProperty(MANAGER_SWARM_ID_PROPERTY));
    if (managerOverride != null) {
      return managerOverride;
    }

    String global = normalise(environment.getProperty(GLOBAL_SWARM_ID_PROPERTY));
    if (global != null) {
      defaults.put(MANAGER_SWARM_ID_PROPERTY, global);
      return global;
    }

    defaults.put(MANAGER_SWARM_ID_PROPERTY, Topology.SWARM_ID);
    if (isBlank(environment.getProperty(GLOBAL_SWARM_ID_PROPERTY))) {
      defaults.put(GLOBAL_SWARM_ID_PROPERTY, Topology.SWARM_ID);
    }
    return Topology.SWARM_ID;
  }

  private static void ensureInstance(
      ConfigurableEnvironment environment, Map<String, Object> defaults, String swarmId) {
    String configuredInstance = normalise(environment.getProperty(INSTANCE_ID_PROPERTY));
    if (configuredInstance != null) {
      System.setProperty(BEE_NAME_PROPERTY, configuredInstance);
      if (isBlank(environment.getProperty(BEE_NAME_PROPERTY))) {
        defaults.put(BEE_NAME_PROPERTY, configuredInstance);
      }
      return;
    }

    String beeName = normalise(environment.getProperty(BEE_NAME_PROPERTY));
    if (beeName == null) {
      beeName = normalise(System.getProperty(BEE_NAME_PROPERTY));
    }
    if (beeName == null) {
      beeName = BeeNameGenerator.generate(ROLE, swarmId);
    }

    defaults.put(INSTANCE_ID_PROPERTY, beeName);
    if (isBlank(environment.getProperty(BEE_NAME_PROPERTY))) {
      defaults.put(BEE_NAME_PROPERTY, beeName);
    }
    System.setProperty(BEE_NAME_PROPERTY, beeName);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String normalise(String value) {
    return isBlank(value) ? null : value;
  }
}
