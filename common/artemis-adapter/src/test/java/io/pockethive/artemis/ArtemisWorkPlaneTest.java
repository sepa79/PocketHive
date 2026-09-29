package io.pockethive.artemis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.api.ArtemisWorkPlane;
import io.pockethive.artemis.topology.ArtemisResourceKind;
import io.pockethive.swarm.model.lifecycle.RemoveResource;
import io.pockethive.swarm.model.lifecycle.RemoveResourceType;
import io.pockethive.swarm.model.lifecycle.ResourcePlane;
import io.pockethive.topology.work.ResolvedWorkTopology;
import io.pockethive.topology.work.WorkResourceIdentity;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemBuilder;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkOutput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtemisWorkPlaneTest {
    @TempDir Path directory;
    private EmbeddedArtemis broker;
    private ArtemisWorkPlane plane;

    @BeforeEach
    void start() throws Exception {
        broker = new EmbeddedArtemis(directory);
        plane = new ArtemisWorkPlane(broker.settings(), "ph");
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (plane != null) plane.close();
        } finally {
            if (broker != null) broker.close();
        }
    }

    @Test
    void createsTransportsObservesAndRemovesOnlyTheSelectedSwarmResources() {
        var topology = plane.topology().resolve("swarm-a", Set.of("jobs"));
        var other = plane.topology().resolve("swarm-b", Set.of("jobs"));
        plane.resources().ensure(topology);
        plane.resources().ensure(other);
        plane.resources().ensure(topology);
        var received = new ConcurrentLinkedQueue<WorkItem>();
        var input = input(topology);
        input.register(handler(received::add));
        input.start();
        var output = output(topology);
        var original = item("hello").messageId("work-1").header("correlationId", "corr-1").build();
        output.publish(original);
        var json = new ObjectMapper();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(received).hasSize(1);
            assertThat(json.readTree(new WorkItemJsonCodec().toJson(received.peek())))
                .isEqualTo(json.readTree(new WorkItemJsonCodec().toJson(original)));
            assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().messages()).isZero();
        });
        input.stop();
        for (var resource : topology.resources().reversed()) {
            var target = plane.resources().removalTarget(resource);
            assertThat(target.type()).isEqualTo(RemoveResourceType.WORK_RESOURCE);
            assertThat(target.plane()).isEqualTo(ResourcePlane.WORK);
            assertThat(plane.resources().identify(target)).isEqualTo(resource);
            plane.resources().remove(plane.resources().identify(target));
            assertThat(plane.resources().observe(resource)).isEmpty();
        }
        for (var resource : other.resources()) assertThat(plane.resources().observe(resource)).isPresent();
        assertThat(plane.resources().appliedResources()).containsExactly(other.channel("jobs").resource());
    }

    @Test
    void stopsAndRestartsWithoutLosingTheRegisteredCallbackOrPendingMessages() {
        var topology = prepare();
        var received = new ConcurrentLinkedQueue<WorkItem>();
        var input = input(topology);
        var output = output(topology);
        assertThatThrownBy(input::start).isInstanceOf(IllegalStateException.class);
        input.register(handler(received::add));
        assertThatThrownBy(() -> input.register(handler(received::add))).isInstanceOf(IllegalStateException.class);
        input.start();
        output.publish(item("first").build());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(1));
        input.stop();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().consumers()).isZero());
        output.publish(item("second").build());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().messages()).isEqualTo(1));
        assertThat(received).hasSize(1);
        input.start();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(2));
        input.stop();
    }

    @Test
    void malformedDataAndFailingCallbacksAreConsumedWithoutBlockingTheNextMessage() throws Exception {
        var topology = prepare();
        var decoded = new AtomicInteger();
        var failures = new AtomicInteger();
        var input = input(topology);
        input.register(new WorkDeliveryHandler() {
            @Override public void onWork(WorkItem item) {
                if (decoded.incrementAndGet() == 1) throw new IllegalArgumentException("test executor failure");
            }
            @Override public void onDecodeFailure(byte[] body, Exception failure) {
                failures.incrementAndGet();
                throw new IllegalStateException("test failure reporter error", failure);
            }
        });
        input.start();
        broker.sendRaw(topology.channel("jobs").outputAddress(), "{broken".getBytes(StandardCharsets.UTF_8));
        var output = output(topology);
        output.publish(item("callback fails").build());
        output.publish(item("next succeeds").build());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(failures.get()).isEqualTo(1);
            assertThat(decoded.get()).isEqualTo(2);
            assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().messages()).isZero();
        });
        input.stop();
        input.start();
        output.publish(item("after restart").build());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(decoded.get()).isEqualTo(3));
        assertThat(failures.get()).isEqualTo(1);
        input.stop();
    }

    @Test
    void acknowledgesAdmissionWithoutWaitingForAsynchronousExecution() throws Exception {
        var topology = prepare();
        var workerStarted = new CountDownLatch(1);
        var finishWorker = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        var input = input(topology);
        try {
            input.register(handler(item -> executor.submit(() -> {
                workerStarted.countDown();
                finishWorker.await(5, TimeUnit.SECONDS);
                throw new IllegalArgumentException("test asynchronous executor failure");
            })));
            input.start();
            output(topology).publish(item("accepted").build());
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(plane.resources().observeInput(topology.channel("jobs").inputAddress()).orElseThrow().messages()).isZero());
            assertThat(finishWorker.getCount()).isEqualTo(1);
        } finally {
            finishWorker.countDown();
            input.stop();
            executor.shutdownNow();
        }
    }

    @Test
    void serializesConcurrentPublicationsAndKeepsConsumerSessionSeparate() throws Exception {
        var topology = prepare();
        var received = new ConcurrentLinkedQueue<WorkItem>();
        var input = input(topology);
        input.register(handler(received::add));
        input.start();
        var output = output(topology);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++) {
                var item = item("result").messageId("item-" + i).build();
                tasks.add(executor.submit(() -> output.publish(item)));
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(received).hasSize(40));
        assertThat(received.stream().map(WorkItem::messageId).distinct().count()).isEqualTo(40);
        input.stop();
    }

    @Test
    void rejectsForeignResourceIntentBeforeDeclaringAnyPartOfTheCandidate() {
        var topology = plane.topology().resolve("swarm", Set.of("jobs"));
        var resources = new ArrayList<>(topology.resources());
        resources.add(new WorkResourceIdentity("RABBITMQ", "QUEUE", "foreign"));
        var invalid = new ResolvedWorkTopology(topology.channels(), resources, Map.of(), Map.of());
        assertThatThrownBy(() -> plane.resources().ensure(invalid)).isInstanceOf(IllegalArgumentException.class);
        for (var resource : topology.resources()) assertThat(plane.resources().observe(resource)).isEmpty();
        assertThatThrownBy(() -> plane.resources().identify(new RemoveResource(
            RemoveResourceType.RABBIT_QUEUE, "foreign", ResourcePlane.CONTROL))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addressStillInUseFailsRemovalAndClosedConnectionIsNotReportedAsAbsence() {
        var topology = prepare();
        var address = topology.resources().stream()
            .filter(resource -> resource.kind().equals(ArtemisResourceKind.ADDRESS.name())).findFirst().orElseThrow();
        assertThatThrownBy(() -> plane.resources().remove(address)).isInstanceOf(IllegalStateException.class);
        assertThat(plane.resources().observe(address)).isPresent();
        plane.close();
        assertThatThrownBy(() -> plane.resources().observe(address)).isInstanceOf(IllegalStateException.class);
    }

    private ResolvedWorkTopology prepare() {
        var topology = plane.topology().resolve("swarm", Set.of("jobs"));
        plane.resources().ensure(topology);
        return topology;
    }

    private static WorkItemBuilder item(String payload) {
        var worker = new WorkerInfo("test-worker", "swarm", "test-worker-1", null, null);
        return WorkItem.text(worker, payload).observabilityContext(
            ObservabilityContextUtil.init(worker.role(), worker.instanceId(), worker.swarmId()));
    }

    private WorkInputChannel input(ResolvedWorkTopology topology) {
        return plane.inputs().create("worker", new ArtemisInputSettings(topology.channel("jobs").inputAddress(), 0));
    }

    private WorkOutput output(ResolvedWorkTopology topology) {
        return plane.outputs().create(new ArtemisOutputSettings(topology.channel("jobs").outputAddress(), true));
    }

    private static WorkDeliveryHandler handler(Consumer<WorkItem> consumer) {
        return new WorkDeliveryHandler() {
            @Override public void onWork(WorkItem item) { consumer.accept(item); }
            @Override public void onDecodeFailure(byte[] body, Exception failure) {
                throw new AssertionError("Unexpected invalid WorkItem", failure);
            }
        };
    }
}
