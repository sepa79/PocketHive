package io.pockethive;

public class Topology {
  // Traffic exchange (hive)
  public static final String EXCHANGE = cfg("PH_TRAFFIC_EXCHANGE", "ph.hive");
  public static final String SWARM_ID = cfg("PH_SWARM_ID", "default");
  public static final String GEN_QUEUE = cfg("PH_GEN_QUEUE", "ph.gen." + SWARM_ID);
  public static final String MOD_QUEUE = cfg("PH_MOD_QUEUE", "ph.mod." + SWARM_ID);
  public static final String FINAL_QUEUE = cfg("PH_FINAL_QUEUE", "ph.final." + SWARM_ID);
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
