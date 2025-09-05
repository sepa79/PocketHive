package io.pockethive;

public class Topology {
  public static final String LOGS_EXCHANGE = cfg("PH_LOGS_EXCHANGE", "ph.logs");
  public static final String LOGS_QUEUE = cfg("PH_LOGS_QUEUE", "ph.logs.agg");

  private static String cfg(String key, String def){
    String v = System.getenv(key);
    if(v==null || v.isBlank()) v = System.getProperty(key);
    return (v==null || v.isBlank()) ? def : v;
  }
}
