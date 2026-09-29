package io.pockethive.processor;

/**
 * Responsibility: provide interruptible waiting for the processor pacing clock boundary.
 * Must not: reserve slots, interpret configuration or execute requests.
 * Contract: RESP-PROCESSOR-PACING — docs/architecture/runtime-responsibilities.md#resp-processor-pacing.
 */
@FunctionalInterface
interface ProcessorPacingSleeper {
  void sleep(long millis, int nanos) throws InterruptedException;
}
