package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import io.pockethive.artemis.api.*;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.work.api.*;
import io.pockethive.work.api.transport.*;
import io.pockethive.work.config.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ArtemisDelayedDeliveryTest {
    @TempDir Path directory;
    private EmbeddedArtemis broker;
    private ArtemisWorkPlane plane;
    private ResolvedWorkTopology topology;
    private final java.util.Map<String, Long> arrivals = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach void start() throws Exception {
        broker = new EmbeddedArtemis(directory);
        plane = new ArtemisWorkPlane(broker.settings(), "ph");
        topology = plane.topology().resolve("delayed", Set.of("jobs"));
        plane.resources().ensure(topology);
    }

    @AfterEach void close() throws Exception {
        try { if (plane != null) plane.close(); }
        finally { if (broker != null) broker.close(); }
    }

    @Test void brokerWaitsWithoutHoldingImmediateTrafficAndPreservesTheEnvelope() throws Exception {
        var received = new LinkedBlockingQueue<WorkItem>();
        var input = consume(received);
        try {
            var output = output();
            var delayed = item("delayed");
            long notBefore = System.currentTimeMillis() + 1500;
            output.publish(delayed, new WorkDelivery(WorkDeliveryMode.DELAYED, 1500));
            await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(pending()).isEqualTo(1));
            output.publish(item("immediate"));
            assertThat(received.poll(1, TimeUnit.SECONDS)).extracting(WorkItem::messageId).isEqualTo("immediate");
            WorkItem delivered = received.poll(5, TimeUnit.SECONDS);
            assertThat(delivered).isNotNull();
            assertThat(arrivals.get("delayed")).isGreaterThanOrEqualTo(notBefore);
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            assertThat(json.readTree(new WorkItemJsonCodec().toJson(delivered)))
                .isEqualTo(json.readTree(new WorkItemJsonCodec().toJson(delayed)));
            assertThat(received).isEmpty();
        } finally { input.stop(); }
    }

    @Test void removalDiscardsScheduledMessagesEvenWhenTheSameChannelIsRecreated() throws Exception {
        output().publish(item("removed"), new WorkDelivery(WorkDeliveryMode.DELAYED, 1500));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(pending()).isEqualTo(1));
        for (var resource : topology.resources().reversed()) {
            plane.resources().remove(resource);
            assertThat(plane.resources().observe(resource)).isEmpty();
        }
        plane.resources().ensure(topology);
        var received = new LinkedBlockingQueue<WorkItem>();
        var input = consume(received);
        try {
            output().publish(item("new"));
            assertThat(received.poll(1, TimeUnit.SECONDS)).extracting(WorkItem::messageId).isEqualTo("new");
            assertThat(received.poll(2, TimeUnit.SECONDS)).isNull();
            assertThat(pending()).isZero();
        } finally { input.stop(); }
    }

    @Test void impossibleTimestampFailsBeforeSend() {
        var output = output();
        assertThatThrownBy(() -> output.publish(item("overflow"), new WorkDelivery(WorkDeliveryMode.DELAYED, Long.MAX_VALUE)))
            .isInstanceOf(ArithmeticException.class);
        assertThat(pending()).isZero();
    }

    private long pending() {
        return plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().messages();
    }

    private WorkOutput output() {
        return plane.outputs().create(new ArtemisOutputSettings(topology.channel("jobs").outputAddress(), true));
    }

    private WorkInputChannel consume(LinkedBlockingQueue<WorkItem> received) {
        var input = plane.inputs().create("worker", new ArtemisInputSettings(topology.channel("jobs").inputAddress(), 0));
        input.register(new WorkDeliveryHandler() {
            @Override public void onWork(WorkItem item) { arrivals.put(item.messageId(), System.currentTimeMillis()); received.add(item); }
            @Override public void onDecodeFailure(byte[] body, Exception failure) { throw new AssertionError(failure); }
        });
        input.start();
        return input;
    }

    private static WorkItem item(String id) {
        var info = new WorkerInfo("test", "delayed", "worker", null, null);
        return WorkItem.text(info, "payload").messageId(id).header("correlationId", "correlation-" + id)
            .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId())).build();
    }
}
