package io.pockethive.httpbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpTemplateLoaderTest {

  @Test
  void loadsJsonTemplate() throws Exception {
    Path dir = Files.createTempDirectory("http-templates");
    Path file = dir.resolve("default-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "CallA",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{}",
          "headersTemplate": {
            "X-Test": "true"
          }
        }
        """);

    HttpTemplateLoader loader = new HttpTemplateLoader();
    Map<String, HttpTemplateDefinition> templates = loader.load(dir.toString(), "default");

    assertThat(templates).hasSize(1);
    HttpTemplateDefinition def = templates.values().iterator().next();
    assertThat(def.serviceId()).isEqualTo("svc");
    assertThat(def.callId()).isEqualTo("CallA");
    assertThat(def.method()).isEqualTo("POST");
    assertThat(def.pathTemplate()).isEqualTo("/test");
    assertThat(def.bodyTemplate()).isEqualTo("{}");
    assertThat(def.headersTemplate()).containsEntry("X-Test", "true");
  }
}

