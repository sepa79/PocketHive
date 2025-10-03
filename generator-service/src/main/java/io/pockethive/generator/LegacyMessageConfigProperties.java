package io.pockethive.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.stereotype.Component;

@Component
@Deprecated
@ConfigurationProperties(prefix = "ph.gen.message")
@SuppressWarnings("DeprecatedIsStillUsed")
public class LegacyMessageConfigProperties {

  private final MessageConfig delegate;

  public LegacyMessageConfigProperties(MessageConfig delegate) {
    this.delegate = delegate;
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.generator.message.path")
  public void setPath(String path) {
    delegate.setPath(path);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.generator.message.method")
  public void setMethod(String method) {
    delegate.setMethod(method);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.generator.message.body")
  public void setBody(String body) {
    delegate.setBody(body);
  }

  @Deprecated
  @DeprecatedConfigurationProperty(replacement = "pockethive.generator.message.headers")
  public void setHeaders(java.util.Map<String, String> headers) {
    delegate.setHeaders(headers);
  }
}
