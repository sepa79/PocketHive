package io.pockethive.swarm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrafficPolicy(@Valid BufferGuardPolicy bufferGuard) {
}
