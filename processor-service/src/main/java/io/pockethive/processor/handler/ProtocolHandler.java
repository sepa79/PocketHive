package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;

/**
 * Responsibility: define one protocol-specific request execution contract.
 * Must not: provision Work/CP topology or let one protocol handler reinterpret another protocol's result.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
public interface ProtocolHandler {
  WorkItem invoke(WorkItem message, JsonNode envelope, ProcessorWorkerConfig config, WorkerContext context) throws Exception;
}
