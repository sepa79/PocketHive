package io.pockethive.acceptance.resources;

import io.pockethive.swarm.model.lifecycle.ControlResponse;
import java.io.IOException;

/**
 * Responsibility: supply one checked API dispatch to the resource's common receipt handling.
 * Must not: own state, wait for completion or retry a mutation.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#resp-acceptance-resources.
 */
@FunctionalInterface
interface ControlCommand {
  ControlResponse send() throws IOException, InterruptedException;
}
