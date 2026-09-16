package io.pockethive.acceptance.capture;

import io.pockethive.acceptance.config.HttpFixture;
import io.pockethive.acceptance.config.WaitLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.operations.Deadline;
import io.pockethive.acceptance.resources.AcquisitionState;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: own one test tap, bounded canonical samples and close.
 * Must not: infer topology or use a native broker client.
 * Contract: RESP-ACCEPTANCE-CAPTURE — docs/architecture/acceptance-tests.md#resp-acceptance-capture.
 */
public final class TapResource implements AutoCloseable {
  private final DebugTapApi api;
  private final WaitLimits limits;
  private final RunEvidence evidence;
  private final WorkItemJsonCodec codec = new WorkItemJsonCodec();
  private AcquisitionState acquisition = AcquisitionState.NOT_REQUESTED;
  private String tapId;
  public TapResource(DebugTapApi api, WaitLimits limits, RunEvidence evidence) {
    this.api = api; this.limits = limits; this.evidence = evidence;
  }
  public void open(String swarmId, HttpFixture fixture) throws IOException, InterruptedException {
    if (acquisition != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Tap already attempted");
    acquisition = AcquisitionState.UNCONFIRMED;
    var response = api.create(swarmId, fixture);
    tapId = response.required("tapId").textValue();
    if (tapId == null || tapId.isBlank()) throw new AssertionError("Tap response has no id");
    acquisition = AcquisitionState.ACQUIRED;
    if (!swarmId.equals(response.required("swarmId").textValue())
        || !fixture.captureRole().equals(response.required("role").textValue())
        || !fixture.captureDirection().equals(response.required("direction").textValue())
        || !fixture.captureIoName().equals(response.required("ioName").textValue())) {
      throw new AssertionError("Tap response does not match requested logical target");
    }
    evidence.record("tap-" + tapId, response);
  }
  public List<WorkItem> awaitSamples(int count) throws IOException, InterruptedException {
    if (acquisition != AcquisitionState.ACQUIRED) throw new IllegalStateException("Tap not open");
    var deadline = new Deadline(limits.capture(), "Samples from tap " + tapId);
    Map<String, WorkItem> items = new LinkedHashMap<>();
    while (true) {
      var response = api.read(tapId, deadline.remaining());
      if (!tapId.equals(response.required("tapId").textValue())) throw new AssertionError("Unrelated tap response");
      evidence.record("tap-" + tapId, response);
      var samples = response.required("samples");
      if (!samples.isArray()) throw new AssertionError("Tap samples are not an array");
      for (var sample : samples) {
        WorkItem item = codec.fromJson(sample.required("payload").textValue().getBytes(StandardCharsets.UTF_8));
        items.putIfAbsent(item.messageId(), item);
        if (items.size() >= count) return List.copyOf(items.values());
      }
      deadline.pause(limits.poll());
    }
  }
  @Override public void close() throws IOException, InterruptedException {
    switch (acquisition) {
      case NOT_REQUESTED, REJECTED, RELEASED -> { }
      case UNCONFIRMED -> throw new AssertionError("Tap acquisition unconfirmed; no id available for cleanup");
      case ACQUIRED -> { api.close(tapId); acquisition = AcquisitionState.RELEASED; }
    }
  }
}
