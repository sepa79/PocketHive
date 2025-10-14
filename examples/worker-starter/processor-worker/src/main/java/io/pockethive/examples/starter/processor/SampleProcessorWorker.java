package io.pockethive.examples.starter.processor;

import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerType;
import org.springframework.stereotype.Component;

/**
 * Demonstrates a processor worker that receives payloads, transforms them, and publishes a new message.
 */
@Component("sampleProcessorWorker")
@PocketHiveWorker(
    role = "processor",
    type = WorkerType.MESSAGE,
    inQueue = "ph.processor.in",
    outQueue = "ph.processor.out"
)
class SampleProcessorWorker implements MessageWorker {

  @Override
  public WorkResult onMessage(WorkMessage message, WorkerContext context) {
    String processedPayload = message.asString().toUpperCase();

    context.statusPublisher()
        .workIn("ph.processor.in")
        .workOut("ph.processor.out")
        .update(status -> status.data("processedPayload", processedPayload));

    WorkMessage outbound = message.toBuilder()
        .textBody(processedPayload)
        .build();

    return WorkResult.message(outbound);
  }
}
