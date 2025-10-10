package io.pockethive.postprocessor;

import io.pockethive.TopologyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph")
class PostProcessorQueuesProperties {

  private String finalQueue = TopologyDefaults.FINAL_QUEUE;

  public String getFinalQueue() {
    return finalQueue;
  }

  public void setFinalQueue(String finalQueue) {
    this.finalQueue = finalQueue;
  }
}

