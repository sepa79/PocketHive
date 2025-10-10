package io.pockethive.moderator;

import io.pockethive.TopologyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph")
class ModeratorQueuesProperties {

  private String genQueue = TopologyDefaults.GEN_QUEUE;
  private String modQueue = TopologyDefaults.MOD_QUEUE;

  public String getGenQueue() {
    return genQueue;
  }

  public void setGenQueue(String genQueue) {
    this.genQueue = genQueue;
  }

  public String getModQueue() {
    return modQueue;
  }

  public void setModQueue(String modQueue) {
    this.modQueue = modQueue;
  }
}

