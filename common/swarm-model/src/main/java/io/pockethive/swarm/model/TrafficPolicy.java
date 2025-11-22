package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrafficPolicy(
    @Valid BufferGuardPolicy bufferGuard,
    @Valid List<BufferGuardPolicy> bufferGuards) {

  public TrafficPolicy(@Valid BufferGuardPolicy bufferGuard) {
    this(bufferGuard, null);
  }
}
