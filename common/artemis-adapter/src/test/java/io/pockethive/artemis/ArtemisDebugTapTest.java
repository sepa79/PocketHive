package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.topology.work.WorkResourceIdentity;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.config.WorkDelivery;
import io.pockethive.work.config.WorkDeliveryMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisDebugTapTest {
    @TempDir Path directory;

    @Test void captureRetainsOnlyNewestCopiesAndCloseRemovesItsResources() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var source = topology.channel("jobs");
            var capture = plane.debugTaps().open("swarm", "processor", "tap1", source, 30, 2);
            try {
                for (int i = 0; i < 5; i++) broker.sendRaw(source.outputAddress(), ("body-" + i).getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5));
                var copies = new ArrayList<String>();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    capture.receive().ifPresent(bytes -> copies.add(new String(bytes, StandardCharsets.UTF_8)));
                    assertThat(copies).containsExactly("body-3", "body-4");
                });
                assertThat(capture.receive()).isEmpty();
                assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5);
            } finally { capture.close(); }
            assertThat(plane.resources().observe(new WorkResourceIdentity("ARTEMIS", ArtemisResourceKind.QUEUE.name(),
                capture.captureAddress()))).isEmpty();
            assertThat(plane.resources().observe(new WorkResourceIdentity("ARTEMIS", ArtemisResourceKind.ADDRESS.name(),
                capture.captureAddress()))).isEmpty();
            // Reopening the same ID also verifies that the native divert and settings were removed.
            try (var reopened = plane.debugTaps().open("swarm", "processor", "tap1", source, 30, 2)) {
                broker.sendRaw(source.outputAddress(), "new".getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(reopened.receive()).isPresent());
            }
        }
    }

    @Test void captureExpiryDiscardsCopiesAndPreservesSourceExpiryPolicy() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var source = topology.channel("jobs");
            try (var capture = plane.debugTaps().open("swarm", "processor", "tap2", source, 1, 2)) {
                broker.sendRaw(source.outputAddress(), "keep original".getBytes(StandardCharsets.UTF_8));
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isEqualTo(1));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isZero());
                assertThat(capture.receive()).isEmpty();
                assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(1);
                assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isZero();

                // A genuine WORK expiration must still reach the broker's expiry queue while the tap is active.
                broker.sendRaw(source.outputAddress(), "expired work".getBytes(StandardCharsets.UTF_8),
                    System.currentTimeMillis() + 500);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isZero();
                    assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(1);
                    assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isEqualTo(1);
                });
            }
            assertThat(plane.resources().observeInput(EmbeddedArtemis.EXPIRY_QUEUE).orElseThrow().messages()).isEqualTo(1);
        }
    }

    @Test void scheduledCopiesRespectRingLimitWhileSourceMessagesKeepTheirDelay() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var source = topology.channel("jobs");
            try (var capture = plane.debugTaps().open("swarm", "processor", "scheduled", source, 30, 2)) {
                var output = plane.outputs().create(new ArtemisOutputSettings(source.outputAddress(), true));
                var info = new WorkerInfo("generator", "swarm", "worker", null, null);
                var expected = new ArrayList<WorkItem>();
                var notBefore = new LinkedHashMap<String, Long>();
                var codec = new WorkItemJsonCodec();
                for (int i = 0; i < 5; i++) {
                    var item = WorkItem.text(info, "body-" + i).messageId("message-" + i)
                        .observabilityContext(ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId()))
                        .build();
                    expected.add(item);
                    notBefore.put(item.messageId(), System.currentTimeMillis() + 3000);
                    output.publish(item, new WorkDelivery(WorkDeliveryMode.DELAYED, 3000));
                }
                await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                    assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5);
                    assertThat(plane.resources().observeInput(capture.captureAddress()).orElseThrow().messages()).isEqualTo(2);
                });
                var copies = new ArrayList<byte[]>();
                await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
                    capture.receive().ifPresent(copies::add);
                    assertThat(copies).hasSize(2);
                });
                assertThat(copies.get(0)).isEqualTo(codec.toJson(expected.get(3)));
                assertThat(copies.get(1)).isEqualTo(codec.toJson(expected.get(4)));
                assertThat(capture.receive()).isEmpty();
                assertThat(plane.resources().observeInput(source.inputAddress()).orElseThrow().messages()).isEqualTo(5);

                var arrivals = new ConcurrentHashMap<String, Long>();
                var received = new LinkedBlockingQueue<WorkItem>();
                var input = plane.inputs().create("source-worker", new ArtemisInputSettings(source.inputAddress(), 0));
                input.register(new WorkDeliveryHandler() {
                    @Override public void onWork(WorkItem item) {
                        arrivals.put(item.messageId(), System.currentTimeMillis());
                        received.add(item);
                    }
                    @Override public void onDecodeFailure(byte[] body, Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
                try {
                    input.start();
                    var receivedIds = new HashSet<String>();
                    for (int i = 0; i < 5; i++) {
                        WorkItem item = received.poll(5, TimeUnit.SECONDS);
                        assertThat(item).isNotNull();
                        assertThat(receivedIds.add(item.messageId())).isTrue();
                        assertThat(arrivals.get(item.messageId())).isGreaterThanOrEqualTo(notBefore.get(item.messageId()));
                        WorkItem original = expected.stream().filter(value -> value.messageId().equals(item.messageId()))
                            .findFirst().orElseThrow();
                        assertThat(codec.toJson(item)).isEqualTo(codec.toJson(original));
                    }
                    assertThat(receivedIds).containsExactlyInAnyOrderElementsOf(notBefore.keySet());
                    assertThat(received).isEmpty();
                } finally { input.stop(); }
            }
        }
    }
}
