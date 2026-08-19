package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpSequenceWorkerConfigTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void deserializesExistingConfigWithoutStepOverrides() throws Exception {
    HttpSequenceWorkerConfig config = mapper.readValue("""
        {
          "baseUrl": "http://worker:8080",
          "templateRoot": "/templates",
          "serviceId": "customers",
          "threadCount": 2,
          "steps": [{"id": "create", "callId": "create"}],
          "debugCapture": {},
          "vars": {}
        }
        """, HttpSequenceWorkerConfig.class);

    assertThat(config.baseUrl()).isEqualTo("http://worker:8080");
    assertThat(config.steps()).singleElement().satisfies(step -> {
      assertThat(step.sutEndpointId()).isNull();
      assertThat(step.baseUrl()).isNull();
    });
  }

  @Test
  void retainsExistingSevenArgumentStepConstructor() {
    HttpSequenceWorkerConfig.Step step = new HttpSequenceWorkerConfig.Step(
        "one", "call", null, false, null, List.of(), List.of());

    assertThat(step.sutEndpointId()).isNull();
    assertThat(step.baseUrl()).isNull();
  }

  @Test
  void rejectsBothStepOverridesWithIndexedFieldPath() {
    HttpSequenceWorkerConfig.Step step = step("accounts", "http://literal:8080");

    assertThatThrownBy(() -> config(step))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0] must not declare both sutEndpointId and baseUrl");
  }

  @Test
  void rejectsBlankSutEndpointOverrideRatherThanTreatingItAsAbsent() {
    assertThatThrownBy(() -> config(step("  ", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].sutEndpointId must not be blank");
  }

  @Test
  void rejectsBlankLiteralOverrideRatherThanTreatingItAsAbsent() {
    assertThatThrownBy(() -> config(step(null, "  ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("steps[0].baseUrl must not be blank");
  }

  @Test
  void exposesTheConfiguredPositiveThreadCountAsMaxInFlight() {
    HttpSequenceWorkerConfig config = config(3, step(null, null));

    assertThat(config.threadCount()).isEqualTo(3);
    assertThat(config.maxInFlight()).isEqualTo(3);
  }

  @Test
  void rejectsZeroThreadCount() {
    assertThatThrownBy(() -> config(0, step(null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("threadCount must be > 0");
  }

  @Test
  void clampsDebugCaptureSamplePercentageToItsContractRange() {
    assertThat(debugCapture(Double.NaN).samplePct()).isZero();
    assertThat(debugCapture(-0.1).samplePct()).isZero();
    assertThat(debugCapture(0.37).samplePct()).isEqualTo(0.37);
    assertThat(debugCapture(1.1).samplePct()).isEqualTo(1.0);
  }

  private HttpSequenceWorkerConfig config(HttpSequenceWorkerConfig.Step step) {
    return config(1, step);
  }

  private HttpSequenceWorkerConfig config(int threadCount, HttpSequenceWorkerConfig.Step step) {
    return new HttpSequenceWorkerConfig(
        "http://worker:8080", "/templates", "customers", threadCount, List.of(step),
        HttpSequenceWorkerConfig.DebugCapture.defaults(), Map.of());
  }

  private static HttpSequenceWorkerConfig.DebugCapture debugCapture(double samplePct) {
    return new HttpSequenceWorkerConfig.DebugCapture(
        HttpSequenceWorkerConfig.DebugCaptureMode.SAMPLE,
        samplePct,
        1,
        1,
        false,
        false,
        0,
        1);
  }

  private static HttpSequenceWorkerConfig.Step step(String sutEndpointId, String baseUrl) {
    return new HttpSequenceWorkerConfig.Step(
        "one", "call", null, false, null, List.of(), List.of(), sutEndpointId, baseUrl);
  }
}
