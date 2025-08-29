package com.example;

public class Topology {
  public static final String EXCHANGE = cfg("PH_EXCHANGE", "sim.direct");
  public static final String GEN_QUEUE = cfg("PH_GEN_QUEUE", "gen.queue");
  public static final String MOD_QUEUE = cfg("PH_MOD_QUEUE", "moderated.queue");
  public static final String STATUS_EXCHANGE = cfg("PH_STATUS_EXCHANGE", "status.exchange");

  private static String cfg(String key, String def){
    String v = System.getenv(key);
    if (v == null || v.isBlank()) v = System.getProperty(key);
    return (v == null || v.isBlank()) ? def : v;
  }
}
