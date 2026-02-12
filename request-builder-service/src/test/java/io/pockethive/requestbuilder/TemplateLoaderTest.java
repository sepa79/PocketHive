package io.pockethive.requestbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateLoaderTest {

  @Test
  void loadsHttpTemplate() throws Exception {
    Path dir = Files.createTempDirectory("http-templates");
    Path file
      = dir.resolve("default-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "CallA",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{}",
          "headersTemplate": {
            "X-Test": "true"
          },
          "resultRules": {
            "businessCode": {
              "source": "RESPONSE_BODY",
              "pattern": "RC=([A-Z0-9]+)"
            },
            "successRegex": "^(00)$"
          }
        }
        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString(), "default");

    assertThat(templates).hasSize(1);
    TemplateDefinition def = templates.values().iterator().next();
    assertThat(def.serviceId()).isEqualTo("svc");
    assertThat(def.callId()).isEqualTo("CallA");
    assertThat(def.bodyTemplate()).isEqualTo("{}");
    assertThat(def.headersTemplate()).containsEntry("X-Test", "true");

    assertThat(def).isInstanceOf(HttpTemplateDefinition.class);
    HttpTemplateDefinition httpDef = (HttpTemplateDefinition) def;
    assertThat(httpDef.method()).isEqualTo("POST");
    assertThat(httpDef.pathTemplate()).isEqualTo("/test");
    assertThat(httpDef.resultRules()).isNotNull();
    assertThat(httpDef.resultRules().successRegex()).isEqualTo("^(00)$");
  }

  @Test
  void loadsTcpTemplate() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates");
    Path file = dir.resolve("tcp-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "TcpCall",
          "protocol": "TCP",
          "behavior": "ECHO",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {},
          "resultRules": {
            "businessCode": {
              "source": "RESPONSE_BODY",
              "pattern": "RC=([A-Z0-9]+)"
            },
            "successRegex": "^(00)$"
          }
        }
        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString(), "default");

    assertThat(templates).hasSize(1);
    TemplateDefinition def = templates.values().iterator().next();
    assertThat(def.serviceId()).isEqualTo("svc");
    assertThat(def.callId()).isEqualTo("TcpCall");
    assertThat(def.protocol()).isEqualTo("TCP");
    assertThat(def.bodyTemplate()).isEqualTo("{{ payload }}");

    assertThat(def).isInstanceOf(TcpTemplateDefinition.class);
    TcpTemplateDefinition tcpDef = (TcpTemplateDefinition) def;
    assertThat(tcpDef.behavior()).isEqualTo("ECHO");
    assertThat(tcpDef.resultRules()).isNotNull();
    assertThat(tcpDef.resultRules().successRegex()).isEqualTo("^(00)$");
  }
}
