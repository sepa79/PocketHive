package io.pockethive.trigger;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.stereotype.Component;

@Component
@Deprecated
@ConfigurationProperties(prefix = "ph.trigger")
@SuppressWarnings("DeprecatedIsStillUsed")
public class LegacyTriggerConfigProperties {

  private final TriggerConfig delegate;

  public LegacyTriggerConfigProperties(TriggerConfig delegate) {
    this.delegate = delegate;
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.interval-ms")
  public void setIntervalMs(long intervalMs) {
    delegate.setIntervalMs(intervalMs);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.action-type")
  public void setActionType(String actionType) {
    delegate.setActionType(actionType);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.command")
  public void setCommand(String command) {
    delegate.setCommand(command);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.url")
  public void setUrl(String url) {
    delegate.setUrl(url);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.method")
  public void setMethod(String method) {
    delegate.setMethod(method);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.body")
  public void setBody(String body) {
    delegate.setBody(body);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.trigger.headers")
  public void setHeaders(Map<String, String> headers) {
    delegate.setHeaders(headers);
  }
}
