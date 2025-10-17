package io.pockethive;

public class Topology {
  public static final String SWARM_ID = cfg("POCKETHIVE_CONTROL_PLANE_SWARM_ID");
  // Traffic exchange (hive)
  public static final String EXCHANGE = cfg("POCKETHIVE_TRAFFIC_EXCHANGE");
  public static final String GEN_QUEUE = cfg("POCKETHIVE_CONTROL_PLANE_QUEUES_GENERATOR");
  public static final String MOD_QUEUE = cfg("POCKETHIVE_CONTROL_PLANE_QUEUES_MODERATOR");
  public static final String FINAL_QUEUE = cfg("POCKETHIVE_CONTROL_PLANE_QUEUES_FINAL");
  // Control queue (shared control plane)
  public static final String CONTROL_QUEUE = cfg("POCKETHIVE_CONTROL_PLANE_CONTROL_QUEUE");
  // Control exchange (topic)
  public static final String CONTROL_EXCHANGE = cfg("POCKETHIVE_CONTROL_PLANE_EXCHANGE");

  private static String cfg(String key) {
    String value = resolve(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing required PocketHive topology configuration for "
              + key
              + ". Provide it via environment variable or JVM system property.");
    }
    return value;
  }

  private static String resolve(String key) {
    if (key == null || key.isBlank()) {
      return null;
    }
    String env = System.getenv(key);
    if (env != null && !env.isBlank()) {
      return env;
    }
    String prop = System.getProperty(key);
    if (prop != null && !prop.isBlank()) {
      return prop;
    }
    return null;
  }
}
