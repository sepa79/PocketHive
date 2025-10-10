package io.pockethive.generator;

import io.pockethive.TopologyDefaults;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph")
class GeneratorQueuesProperties {

  private String genQueue = TopologyDefaults.GEN_QUEUE;

  public String getGenQueue() {
    return genQueue;
  }

  public void setGenQueue(String genQueue) {
    this.genQueue = genQueue;
  }
}

