package io.pockethive.functionalswarm.contracts;

/** Canonical names and version for Functional Swarm RPC. */
public final class FunctionalSwarmProtocol {
  public static final String VERSION = "1.0";
  public static final String REPLY_LIST_HEADER = "x-ph-functional-swarm-reply-list";
  public static final String REQUEST_ID_HEADER = "x-ph-functional-swarm-request-id";

  private FunctionalSwarmProtocol() {
  }
}
