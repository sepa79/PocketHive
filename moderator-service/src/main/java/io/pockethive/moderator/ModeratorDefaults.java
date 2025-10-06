package io.pockethive.moderator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ph.moderator")
class ModeratorDefaults {

  private boolean enabled = false;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  ModeratorWorkerConfig asConfig() {
    return new ModeratorWorkerConfig(enabled);
  }
}
