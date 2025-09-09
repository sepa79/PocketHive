package io.pockethive.swarmcontroller;

public interface SwarmLifecycle {
  void start(String planJson);
  void stop();
  SwarmStatus getStatus();
}
