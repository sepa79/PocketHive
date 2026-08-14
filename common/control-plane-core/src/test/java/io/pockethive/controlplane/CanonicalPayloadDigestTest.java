package io.pockethive.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalPayloadDigestTest {

  @Test
  void mapInsertionOrderDoesNotChangeDigest() {
    Map<String, Object> first = new LinkedHashMap<>();
    first.put("enabled", true);
    first.put("config", Map.of("b", 2, "a", 1));
    Map<String, Object> second = new LinkedHashMap<>();
    second.put("config", Map.of("a", 1, "b", 2));
    second.put("enabled", true);

    assertThat(CanonicalPayloadDigest.sha256(new ObjectMapper(), first))
        .isEqualTo(CanonicalPayloadDigest.sha256(new ObjectMapper(), second))
        .startsWith("sha256:");
  }
}
