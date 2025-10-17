package io.pockethive.scenarios;

import io.pockethive.Topology;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;

public final class ScenarioManagerControlPlaneInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

  private static final String PROPERTY_SOURCE_NAME = "scenarioManagerControlPlaneDefaults";
  private static final String APPLICATION_NAME_PROPERTY = "spring.application.name";
  private static final String ROLE_PROPERTY = "pockethive.control-plane.manager.role";
  private static final String INSTANCE_ID_PROPERTY = "pockethive.control-plane.manager.instance-id";
  private static final String GLOBAL_SWARM_ID_PROPERTY = "pockethive.control-plane.swarm-id";

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    ConfigurableEnvironment environment = applicationContext.getEnvironment();
    Map<String, Object> defaults = new HashMap<>();

    ensureApplicationName(environment, defaults);
    ensureRole(environment, defaults);
    ensureSwarmId(environment, defaults);
    ensureInstance(environment, defaults);

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
      defaults.put(APPLICATION_NAME_PROPERTY, resolveRole(environment));
    }
  }

  private static void ensureRole(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    if (isBlank(environment.getProperty(ROLE_PROPERTY))) {
      defaults.put(ROLE_PROPERTY, resolveRole(environment));
    }
  }

  private static void ensureSwarmId(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    if (isBlank(environment.getProperty(GLOBAL_SWARM_ID_PROPERTY))) {
      defaults.put(GLOBAL_SWARM_ID_PROPERTY, Topology.SWARM_ID);
    }
  }

  private static void ensureInstance(
      ConfigurableEnvironment environment, Map<String, Object> defaults) {
    if (isBlank(environment.getProperty(INSTANCE_ID_PROPERTY))) {
      defaults.put(INSTANCE_ID_PROPERTY, resolveRole(environment));
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String resolveRole(ConfigurableEnvironment environment) {
    String configuredRole = environment.getProperty(ROLE_PROPERTY);
    if (!isBlank(configuredRole)) {
      return configuredRole;
    }

    for (PropertySource<?> source : environment.getPropertySources()) {
      Object candidate = source.getProperty(ROLE_PROPERTY);
      if (candidate instanceof String role && !isBlank(role)) {
        return role;
      }
    }

    throw new IllegalStateException(
        "Missing configured value for " + ROLE_PROPERTY + " and no default found in property sources");
  }
}
