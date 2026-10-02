package io.pockethive.requestbuilder;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.requesttemplates.Iso8583TemplateDefinition;
import io.pockethive.requesttemplates.RequestTemplateParser;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.work.api.IsoSchemaRef;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Iso8583ExampleLayoutTest {
  @Test void documentedLayoutsRenderThroughExistingParserAndCompileWithSyntheticPack() throws Exception {
    Path repository = Path.of("..").toAbsolutePath().normalize();
    Path schemaRoot = repository.resolve("processor-service/src/test/resources/iso8583/schema-registry");
    var codec = new Iso8583Codec();
    var renderer = new MessageTemplateRenderer(new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE));
    var seed = WorkItem.text(new WorkerInfo("demo", "generator", "demo", "input", "output"),
        "{\"transmissionDate\":\"1002123045\",\"stan\":\"000001\"}").build();
    for (String name : java.util.List.of("authorization", "echo")) {
      var document = new ObjectMapper().readValue(Files.readString(repository.resolve(
          "docs/examples/iso8583-mip/templates/synthetic/" + name + ".json")), Map.class);
      var template = (Iso8583TemplateDefinition) new RequestTemplateParser().parse(document);
      var rendered = renderer.render(MessageTemplate.builder().bodyType(MessageBodyType.SIMPLE)
          .bodyTemplate(template.bodyTemplate()).headerTemplates(template.headersTemplate()).build(), seed);
      var selected = template.schemaRef();
      var schema = new IsoSchemaRef(schemaRoot.toString(), selected.schemaId(), selected.schemaVersion(),
          selected.schemaAdapter(), selected.schemaFile());
      var decoded = codec.decode(codec.encodePayload(rendered.body(), schema), schema);
      assertThat(decoded.field(11)).isEqualTo("000001");
      assertThat(decoded.field(7)).isEqualTo("1002123045");
      if (name.equals("authorization")) {
        assertThat(decoded.mti()).isEqualTo(0x0100);
        assertThat(decoded.field(4)).isEqualTo("000000000123");
      } else {
        assertThat(decoded.mti()).isEqualTo(0x0800);
        assertThat(decoded.field(70)).isEqualTo("270");
      }
    }
  }
}
