package io.pockethive.requestbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.Iso8583TemplateDefinition;
import io.pockethive.requesttemplates.TcpTemplateDefinition;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.requesttemplates.TemplateLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateLoaderTest {

  @Test
  void loadsHttpTemplate() throws Exception {
    Path dir = Files.createTempDirectory("templates");
    Path file
      = dir.resolve("default-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "CallA",
          "protocol": "HTTP",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{}",
          "headersTemplate": {
            "X-Test": "true"
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
  }

  @Test
  void failsWhenProtocolMissing() throws Exception {
    Path dir = Files.createTempDirectory("missing-protocol-templates");
    Path file = dir.resolve("default-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "CallA",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{}",
          "headersTemplate": {}
        }
        """);

    TemplateLoader loader = new TemplateLoader();
    assertThatThrownBy(() -> loader.load(dir.toString(), "default"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse template");
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
          "headersTemplate": {}
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
  }

  @Test
  void loadsIso8583Template() throws Exception {
    Path dir = Files.createTempDirectory("iso-templates");
    Path file = dir.resolve("iso-call.json");
    Files.writeString(file, """
        {
          "serviceId": "svc",
          "callId": "IsoCall",
          "protocol": "ISO8583",
          "wireProfileId": "MC_2BYTE_LEN_BIN_BITMAP",
          "payloadAdapter": "FIELD_LIST_XML",
          "bodyTemplate": "<iso8583 mti=\\"0100\\"/>",
          "headersTemplate": {
            "x-iso-flow": "ctap"
          },
          "schemaRef": {
            "schemaRegistryRoot": "/app/scenario/iso-schemas",
            "schemaId": "ctap-belgium-auth",
            "schemaVersion": "1.0.0",
            "schemaAdapter": "J8583_XML",
            "schemaFile": "ctap.xml"
          }
        }
        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString(), "default");

    assertThat(templates).hasSize(1);
    TemplateDefinition def = templates.values().iterator().next();
    assertThat(def.serviceId()).isEqualTo("svc");
    assertThat(def.callId()).isEqualTo("IsoCall");
    assertThat(def.protocol()).isEqualTo("ISO8583");
    assertThat(def).isInstanceOf(Iso8583TemplateDefinition.class);

    Iso8583TemplateDefinition isoDef = (Iso8583TemplateDefinition) def;
    assertThat(isoDef.wireProfileId()).isEqualTo("MC_2BYTE_LEN_BIN_BITMAP");
    assertThat(isoDef.payloadAdapter()).isEqualTo("FIELD_LIST_XML");
    assertThat(isoDef.bodyTemplate()).isEqualTo("<iso8583 mti=\"0100\"/>");
    assertThat(isoDef.headersTemplate()).containsEntry("x-iso-flow", "ctap");
    assertThat(isoDef.schemaRef()).isNotNull();
    assertThat(isoDef.schemaRef().schemaFile()).isEqualTo("ctap.xml");
  }
}
