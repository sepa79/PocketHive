package io.pockethive.swarm.model.lifecycle;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum TerminalStatus {
  SUCCEEDED("Succeeded"),
  REJECTED("Rejected"),
  FAILED("Failed"),
  TIMED_OUT("TimedOut");

  private final String wireValue;

  TerminalStatus(String wireValue) {
    this.wireValue = wireValue;
  }

  @JsonValue
  public String wireValue() {
    return wireValue;
  }

  @JsonCreator
  public static TerminalStatus fromWireValue(String value) {
    return Arrays.stream(values())
        .filter(status -> status.wireValue.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown terminal status: " + value));
  }
}
