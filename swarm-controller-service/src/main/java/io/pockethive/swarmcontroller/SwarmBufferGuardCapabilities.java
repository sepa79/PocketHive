package io.pockethive.swarmcontroller;

import io.pockethive.manager.guard.BufferGuardSettings;
import java.util.List;

/** Required buffer-guard configuration and observation capabilities. */
public interface SwarmBufferGuardCapabilities {

  List<BufferGuardSettings> bufferGuards();

  void configureBufferGuards(List<BufferGuardSettings> settings);

  boolean bufferGuardActive();

  String bufferGuardProblem();
}
