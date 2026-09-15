package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputChannelState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisWorkInputChannelTest {
    @TempDir Path directory;

    @Test
    void brokerLossStopsTheChannelAndExplicitStartCannotReportSuccess() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var input = startInput(plane);
            broker.close();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(input.state()).isEqualTo(WorkInputChannelState.STOPPED));
            assertThatThrownBy(input::start).isInstanceOf(IllegalStateException.class);
            assertThat(input.state()).isEqualTo(WorkInputChannelState.STOPPED);
            input.stop();
            assertThat(input.state()).isEqualTo(WorkInputChannelState.STOPPED);
        }
    }

    @Test
    void closingTheAdapterStopsItsInputAndPreventsFalseSuccessfulRestart() throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var input = startInput(plane);
            plane.close();

            assertThat(input.state()).isEqualTo(WorkInputChannelState.STOPPED);
            assertThatThrownBy(input::start).isInstanceOf(IllegalStateException.class);
            assertThat(input.state()).isEqualTo(WorkInputChannelState.STOPPED);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void consumingLaterDeliveryDoesNotAcknowledgeAnEarlierUnacceptedItem(boolean malformed) throws Exception {
        try (var broker = new EmbeddedArtemis(directory);
             var plane = new ArtemisWorkPlane(broker.settings(), "ph")) {
            var topology = plane.topology().resolve("swarm", Set.of("jobs"));
            plane.resources().ensure(topology);
            var route = topology.channel("jobs");
            var input = plane.inputs().create("worker", new ArtemisInputSettings(route.inputAddress(), 1024 * 1024));
            var resume = new java.util.concurrent.atomic.AtomicBoolean();
            var later = new java.util.concurrent.CountDownLatch(1);
            var accepted = new java.util.concurrent.ConcurrentLinkedQueue<String>();
            input.register(new WorkDeliveryHandler() {
                @Override public void onWork(WorkItem item) {
                    if (item.messageId().equals("waiting") && !resume.get()) {
                        throw new io.pockethive.work.api.transport.WorkNotAcceptedException("Paused");
                    }
                    accepted.add(item.messageId());
                    if (item.messageId().equals("later")) later.countDown();
                }
                @Override public void onDecodeFailure(byte[] body, Exception failure) { later.countDown(); }
            });
            var output = plane.outputs().create(new io.pockethive.artemis.api.ArtemisOutputSettings(route.outputAddress(), true));
            output.publish(item("waiting"));
            if (malformed) broker.sendRaw(route.outputAddress(), "bad-json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            else output.publish(item("later"));
            input.start();
            assertThat(later.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            input.stop();
            assertThat(plane.resources().observeInput(route.inputAddress()).orElseThrow().messages()).isEqualTo(1);
            assertThat(accepted).doesNotContain("waiting");

            resume.set(true);
            input.start();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(accepted.stream().filter("waiting"::equals).count()).isEqualTo(1);
                assertThat(plane.resources().observeInput(route.inputAddress()).orElseThrow().messages()).isZero();
            });
            input.stop();
        }
    }

    private static WorkItem item(String id) {
        var worker = new io.pockethive.work.api.WorkerInfo("processor", "swarm", "worker", null, null);
        return WorkItem.text(worker, "payload").messageId(id).observabilityContext(
            io.pockethive.observability.ObservabilityContextUtil.init(worker.role(), worker.instanceId(), worker.swarmId())).build();
    }

    private static WorkInputChannel startInput(ArtemisWorkPlane plane) {
        var topology = plane.topology().resolve("swarm", Set.of("jobs"));
        plane.resources().ensure(topology);
        var input = plane.inputs().create("worker", new ArtemisInputSettings(topology.channel("jobs").inputAddress(), 0));
        input.register(new WorkDeliveryHandler() {
            @Override public void onWork(WorkItem item) { throw new AssertionError("No message was sent"); }
            @Override public void onDecodeFailure(byte[] body, Exception failure) { throw new AssertionError(failure); }
        });
        input.start();
        assertThat(input.state()).isEqualTo(WorkInputChannelState.RUNNING);
        return input;
    }
}
