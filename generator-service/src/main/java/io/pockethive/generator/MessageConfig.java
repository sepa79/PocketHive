package io.pockethive.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "ph.gen.message")
public class MessageConfig {
  private String path = "/api/test";
  private String method = "POST";
  private String body = "hello-world";
  private Map<String, String> headers = new HashMap<>();

  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }

  public String getMethod() { return method; }
  public void setMethod(String method) { this.method = method; }

  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }

  public Map<String, String> getHeaders() { return headers; }
  public void setHeaders(Map<String, String> headers) { this.headers = headers; }
}
