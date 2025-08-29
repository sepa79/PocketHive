package com.example;

public class Topology {
  // Traffic exchange (hive)
  public static final String EXCHANGE = cfg("PH_TRAFFIC_EXCHANGE", "ph.hive");
  public static final String GEN_QUEUE = cfg("PH_GEN_QUEUE", "ph.gen");
  public static final String MOD_QUEUE = cfg("PH_MOD_QUEUE", "ph.mod");
  // Control queue (shared control plane)
  public static final String CONTROL_QUEUE = cfg("PH_CONTROL_QUEUE", "ph.control");

  private static String cfg(String key, String def){
    String v = System.getenv(key);
    if (v == null || v.isBlank()) v = System.getProperty(key);
    return (v == null || v.isBlank()) ? def : v;
  }
}
