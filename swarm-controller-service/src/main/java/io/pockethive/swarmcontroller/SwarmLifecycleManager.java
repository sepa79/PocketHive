package io.pockethive.swarmcontroller;

import io.pockethive.Topology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SwarmLifecycleManager implements SwarmLifecycle {
  private static final Logger log = LoggerFactory.getLogger(SwarmLifecycleManager.class);

  @Override
  public void start() {
    log.info("Starting swarm {}", Topology.SWARM_ID);
  }

  @Override
  public void stop() {
    log.info("Stopping swarm {}", Topology.SWARM_ID);
  }
}
