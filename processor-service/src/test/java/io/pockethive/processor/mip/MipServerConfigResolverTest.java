package io.pockethive.processor.mip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.processor.ProcessorWorkerConfig;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MipServerConfigResolverTest {
  private final MipServerConfigResolver resolver = new MipServerConfigResolver(new ObjectMapper());

  @Test void resolvesOnlyExplicitAdapterAndRequiredSettings() {
    var config = resolver.resolve(worker("mip://127.0.0.1:6036", settings()));
    assertThat(config.endpoint().port()).isEqualTo(6036);
    assertThat(config.settings().networkManagement().supportedCodes()).containsExactlyInAnyOrder("270", "081", "082");
    assertThatThrownBy(() -> resolver.resolve(worker("tcp://127.0.0.1:6036", settings())))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mip://");
    assertThatThrownBy(() -> resolver.resolve(worker("mip://127.0.0.1:6036", Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void rejectsMissingUnknownAndInvalidCapacityWithoutExposingPrivateValues() {
    for (var mutate : java.util.List.<java.util.function.Consumer<Map<String, Object>>>of(
        config -> config.remove("maxPending"),
        config -> config.put("maxPending", 0),
        config -> config.put("maxPending", 1_000_001),
        config -> config.put("schemaRef", null),
        config -> config.put("typo", "private-test-marker"))) {
      var config = new HashMap<String, Object>(settings());
      mutate.accept(config);
      assertThatThrownBy(() -> resolver.resolve(worker("mip://127.0.0.1:6036", config)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid privateConfig.mipServer settings")
          .hasNoCause();
    }
  }

  @Test void rejectsInvalidLayoutAndAmbiguousEndpoint() {
    var config = new HashMap<String, Object>(settings());
    config.put("networkManagement", Map.of("supportedCodes", java.util.List.of("270"),
        "copyFields", java.util.List.of(11), "responseFields", Map.of("39", "00")));
    assertThatThrownBy(() -> resolver.resolve(worker("mip://127.0.0.1:6036", config)))
        .isInstanceOf(IllegalArgumentException.class);
    for (String endpoint : java.util.List.of("mip://127.0.0.1", "mip://127.0.0.1:6036/path", "mip://user@localhost:6036")) {
      assertThatThrownBy(() -> resolver.resolve(worker(endpoint, settings())))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  static Map<String, Object> settings() {
    return Map.of("schemaRef", Map.of("schemaRegistryRoot", "/synthetic", "schemaId", "synthetic",
        "schemaVersion", "1", "schemaAdapter", "J8583_XML", "schemaFile", "synthetic-j8583.xml"),
        "maxPending", 8,
        "networkManagement", Map.of("supportedCodes", java.util.List.of("270", "081", "082"),
            "copyFields", java.util.List.of(7, 11, 70), "responseFields", Map.of("39", "00")));
  }

  static ProcessorWorkerConfig worker(String endpoint, Map<String, Object> settings) {
    return new ProcessorWorkerConfig(endpoint, ProcessorWorkerConfig.Mode.THREAD_COUNT,
        8, null, ProcessorWorkerConfig.ConnectionReuse.GLOBAL, true, 1000, false, null,
        settings.isEmpty() ? Map.of() : Map.of(MipServerConfigResolver.SETTINGS_KEY, settings));
  }
}
