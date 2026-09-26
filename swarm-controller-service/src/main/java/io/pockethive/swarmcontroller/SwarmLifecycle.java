package io.pockethive.swarmcontroller;

import io.pockethive.manager.guard.BufferGuardSettings;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: Expose the complete mandatory Swarm Controller runtime capabilities.
 * Must not: Supply default state, no-op behavior or alternate lifecycle decisions.
 * Contract: RESP-CONTROLLER-CONTROL — docs/architecture/runtime-responsibilities.md#resp-controller-control.
 */
public interface SwarmLifecycle extends SwarmLifecycleCore {

  Map<String, Object> scenarioProgress();

  List<BufferGuardSettings> bufferGuards();

  void configureBufferGuards(List<BufferGuardSettings> settings);

  boolean bufferGuardActive();

  String bufferGuardProblem();
}
