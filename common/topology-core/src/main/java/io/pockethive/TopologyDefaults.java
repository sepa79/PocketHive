package io.pockethive;

/**
 * Compile-time constants for the default PocketHive topology. These values mirror the
 * environment-derived values exposed via {@link Topology} but remain safe for use in
 * annotation attributes and other constant-expression contexts.
 */
public final class TopologyDefaults {

  private TopologyDefaults() {
  }

  public static final String SWARM_ID = "default";

  public static final String EXCHANGE = "ph." + SWARM_ID + ".hive";

  public static final String GEN_QUEUE = "ph." + SWARM_ID + ".gen";

  public static final String MOD_QUEUE = "ph." + SWARM_ID + ".mod";

  public static final String FINAL_QUEUE = "ph." + SWARM_ID + ".final";

  public static final String CONTROL_QUEUE = "ph.control";

  public static final String CONTROL_EXCHANGE = "ph.control";
}