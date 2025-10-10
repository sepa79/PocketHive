package io.pockethive.processor;

import io.pockethive.TopologyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph")
class ProcessorQueuesProperties {

  private String modQueue = TopologyDefaults.MOD_QUEUE;
  private String finalQueue = TopologyDefaults.FINAL_QUEUE;

  public String getModQueue() {
    return modQueue;
  }

  public void setModQueue(String modQueue) {
    this.modQueue = modQueue;
  }

  public String getFinalQueue() {
    return finalQueue;
  }

  public void setFinalQueue(String finalQueue) {
    this.finalQueue = finalQueue;
  }
}

