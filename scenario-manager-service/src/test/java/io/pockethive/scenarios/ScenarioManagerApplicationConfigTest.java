package io.pockethive.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScenarioManagerApplicationConfigTest {

  private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

  @Test
  void applicationConfigExposesInfoAndResolvedReleaseVersion() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
      assertThat(input).as("filtered application.yml").isNotNull();
      Map<String, Object> root = yaml.readValue(input, new TypeReference<>() {
      });

      Map<String, Object> management = nestedMap(root, "management");
      Map<String, Object> endpoints = nestedMap(management, "endpoints");
      Map<String, Object> web = nestedMap(endpoints, "web");
      Map<String, Object> exposure = nestedMap(web, "exposure");
      assertThat(exposure.get("include")).isEqualTo("health,info");

      Map<String, Object> pockethive = nestedMap(root, "pockethive");
      Map<String, Object> release = nestedMap(pockethive, "release");
      assertThat(release.get("version")).isInstanceOf(String.class);
      String version = (String) release.get("version");
      assertThat(version).isNotEqualTo("@project.version@").isNotBlank();
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> nestedMap(Map<String, Object> source, String key) {
    assertThat(source).containsKey(key);
    assertThat(source.get(key)).isInstanceOf(Map.class);
    return (Map<String, Object>) source.get(key);
  }
}
