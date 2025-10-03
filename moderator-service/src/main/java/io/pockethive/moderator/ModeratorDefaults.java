package io.pockethive.moderator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class ModeratorDefaults {

  private final boolean defaultEnabled;

  ModeratorDefaults(@Value("${ph.moderator.enabled:false}") boolean defaultEnabled) {
    this.defaultEnabled = defaultEnabled;
  }

  ModeratorWorkerConfig asConfig() {
    return new ModeratorWorkerConfig(defaultEnabled);
  }
}
