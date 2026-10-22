package io.pockethive.requesttemplates.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.Iso8583TemplateDefinition;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.RequestTemplateException;
import io.pockethive.requesttemplates.TcpTemplateDefinition;
import io.pockethive.requesttemplates.TemplateDefinition;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
          },
	          "resultRules": {
	            "businessCode": {
	              "source": "RESPONSE_BODY",
	              "pattern": "RC=([A-Z0-9]+)"
	            },
	            "successRegex": "^(00)$",
	            "dimensions": [
	              {
	                "name": "segment",
	                "source": "REQUEST_HEADER",
	                "header": "X-Segment",
	                "pattern": "(.+)"
	              }
	            ]
	          }
	        }
	        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString());

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
	    assertThat(httpDef.resultRules().dimensions()).hasSize(1);
	    assertThat(httpDef.resultRules().dimensions().get(0).name()).isEqualTo("segment");
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
    assertThatThrownBy(() -> loader.load(dir.toString()))
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
          "headersTemplate": {},
	          "resultRules": {
	            "businessCode": {
	              "source": "RESPONSE_BODY",
	              "pattern": "RC=([A-Z0-9]+)"
	            },
	            "successRegex": "^(00)$",
	            "dimensions": [
	              {
	                "name": "segment",
	                "source": "REQUEST_HEADER",
	                "header": "X-Segment",
	                "pattern": "(.+)"
	              }
	            ]
	          }
	        }
	        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString());

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
	    assertThat(tcpDef.resultRules().dimensions()).hasSize(1);
	    assertThat(tcpDef.resultRules().dimensions().get(0).name()).isEqualTo("segment");
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
    Map<String, TemplateDefinition> templates = loader.load(dir.toString());

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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rejectsLegacyInlineAuth(boolean alsoHasAuthRef) throws Exception {
    Path dir = Files.createTempDirectory("legacy-auth-template");
    Files.writeString(dir.resolve("call.yaml"), """
        serviceId: svc
        callId: CallA
        protocol: HTTP
        method: GET
        pathTemplate: /test
        bodyTemplate: ""
        auth:
          type: STATIC_TOKEN
          token: bad
        """ + (alsoHasAuthRef ? "authRef: {profileId: token, applyAs: HTTP_HEADER}\n" : ""));

    TemplateLoader loader = new TemplateLoader();

    assertThatThrownBy(() -> loader.load(dir.toString()))
        .isInstanceOf(IllegalStateException.class)
        .satisfies(ex -> {
          assertThat(ex.getCause()).isInstanceOf(AuthFailureException.class)
              .hasMessageContaining("inline auth");
          assertThat(AuthFailureException.find(ex)).hasValueSatisfying(failure ->
              assertThat(failure.stage()).isEqualTo("configuration"));
        });
  }

  @Test
  void rejectsDuplicateYamlKeys() throws Exception {
    Path dir = Files.createTempDirectory("duplicate-template-keys");
    Files.writeString(dir.resolve("call.yaml"), """
        serviceId: svc
        callId: CallA
        callId: CallB
        protocol: HTTP
        method: GET
        pathTemplate: /test
        bodyTemplate: ""
        """);

    TemplateLoader loader = new TemplateLoader();

    assertThatThrownBy(() -> loader.load(dir.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to parse template");
  }

  @Test
  void ignoresAuthProfilesYamlWhenScanningTemplates() throws Exception {
    Path dir = Files.createTempDirectory("templates-with-auth-profiles");
    Files.writeString(dir.resolve("authProfiles.yaml"), """
        profiles:
          bearer:
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: ignored-by-template-loader
        """);
    Files.writeString(dir.resolve("call.yaml"), """
        serviceId: svc
        callId: CallA
        protocol: HTTP
        method: GET
        pathTemplate: /test
        bodyTemplate: ""
        headersTemplate: {}
        """);

    TemplateLoader loader = new TemplateLoader();
    Map<String, TemplateDefinition> templates = loader.load(dir.toString());

    assertThat(templates).hasSize(1);
    assertThat(templates).containsKey("svc::CallA");
  }
  @Test
  void rejectsMissingRoot() throws Exception {
    Path root = Files.createTempDirectory("template-root").resolve("missing");
    assertThatThrownBy(() -> new TemplateLoader().load(root.toString()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("existing directory");
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "true", "trailing"})
  void rejectsTrailingJsonInput(String trailing) throws Exception {
    Path root = Files.createTempDirectory("trailing-template-json");
    Files.writeString(root.resolve("call.json"), """
        {"protocol":"HTTP","serviceId":"svc","callId":"call","method":"GET","pathTemplate":"/"}
        """ + trailing);
    assertThatThrownBy(() -> new TemplateLoader().load(root.toString()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to parse template");
  }

  @Test
  void acceptsTrailingJsonWhitespace() throws Exception {
    Path root = Files.createTempDirectory("template-json-whitespace");
    Files.writeString(root.resolve("call.json"), """
        {"protocol":"HTTP","serviceId":"svc","callId":"call","method":"GET","pathTemplate":"/"}
         \t\r\n
        """);
    assertThat(new TemplateLoader().load(root.toString())).containsKey("svc::call");
  }

  @Test
  void rejectsDuplicateJsonKeys() throws Exception {
    Path root = Files.createTempDirectory("duplicate-template-json");
    Files.writeString(root.resolve("call.json"), """
        {"protocol":"HTTP","serviceId":"svc","callId":"one","callId":"two","method":"GET","pathTemplate":"/"}
        """);
    assertThatThrownBy(() -> new TemplateLoader().load(root.toString()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Failed to parse template");
  }

  @Test
  void rejectsDuplicateTemplateKeys() throws Exception {
    Path root = Files.createTempDirectory("duplicate-templates");
    String template = "protocol: HTTP\nserviceId: svc\ncallId: call\nmethod: GET\npathTemplate: /\n";
    Files.writeString(root.resolve("one.yaml"), template);
    Files.writeString(root.resolve("two.yaml"), template);
    assertThatThrownBy(() -> new TemplateLoader().load(root.toString()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate request template svc::call");
  }

  @ParameterizedTest
  @ValueSource(strings = {"json", "yaml"})
  void rejectsPortableTemplatesWithUnpairedSurrogates(String extension) throws Exception {
    Path root = Files.createTempDirectory("portable-invalid-unicode");
    String source;
    try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
      assertThat(resource).isNotNull();
      source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
    Files.writeString(root.resolve("call." + extension),
        source.replace("\"bodyTemplate\": \"", "\"bodyTemplate\": \"\\uD800"));

    assertThatThrownBy(() -> new TemplateLoader().load(root.toString()))
        .isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(RequestTemplateException.class)
        .hasStackTraceContaining("paired UTF-16 surrogates");
  }

  @ParameterizedTest
  @ValueSource(strings = {"json", "yaml"})
  void loadsPortableTemplatesWithPairedUnicode(String extension) throws Exception {
    Path root = Files.createTempDirectory("portable-valid-unicode");
    String source;
    try (var resource = getClass().getResourceAsStream("/portable/examples/account-json.json")) {
      assertThat(resource).isNotNull();
      source = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
    Files.writeString(root.resolve("call." + extension),
        source.replace("{{ endpoint.path }}", "/zażółć/\\uD83D\\uDE00"));

    var loaded = new TemplateLoader().load(root.toString());
    assertThat(loaded.get("accounts::create")).isInstanceOfSatisfying(PortableHttpCall.class, call -> {
      assertThat(call.request().pathTemplate()).isEqualTo("/zażółć/😀");
      assertThat(call.response().expectedStatus()).isEqualTo(201);
    });
  }

}
