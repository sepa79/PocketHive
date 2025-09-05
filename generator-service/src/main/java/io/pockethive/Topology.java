package io.pockethive;

public class Topology {
  public static final String SWARM_ID = cfg("PH_SWARM_ID", "default");
  // Traffic exchange (hive)
  public static final String EXCHANGE = cfg("PH_TRAFFIC_EXCHANGE", "ph." + SWARM_ID + ".hive");
  public static final String GEN_QUEUE = cfg("PH_GEN_QUEUE", "ph." + SWARM_ID + ".gen");
  public static final String MOD_QUEUE = cfg("PH_MOD_QUEUE", "ph." + SWARM_ID + ".mod");
  // Control queue (shared control plane)
  public static final String CONTROL_QUEUE = cfg("PH_CONTROL_QUEUE", "ph.control");
  // Control exchange (topic)
  public static final String CONTROL_EXCHANGE = cfg("PH_CONTROL_EXCHANGE", "ph.control");

  private static String cfg(String key, String def){
    String v = System.getenv(key);
    if (v == null || v.isBlank()) v = System.getProperty(key);
    return (v == null || v.isBlank()) ? def : v;
  }
}
