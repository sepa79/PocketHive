package io.pockethive.swarmcontroller;

public interface SwarmLifecycle {
  void prepare(String templateJson);
  void start(String planJson);
  void stop();
  SwarmStatus getStatus();
}
