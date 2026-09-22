package io.pockethive.worker.sdk.testing;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkNotAcceptedException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(20)
class InMemoryWorkTransportTest {
    private static final String ADDRESS = "memory://flow/input";
    private static final WorkerInfo INFO = new WorkerInfo("processor", "swarm", "instance", null, null);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentPublicationRetainsEveryItemOnce(boolean running) throws Exception {
        var transport = new InMemoryWorkTransport();
        transport.create(ADDRESS);
        var input = transport.input(ADDRESS);
        var output = transport.output(ADDRESS);
        var received = new ConcurrentLinkedQueue<WorkItem>();
        input.register(handler(received::add));
        if (running) input.start();

        int publishers = 8;
        int perPublisher = 1000;
        List<WorkItem> items = IntStream.range(0, publishers * perPublisher)
            .mapToObj(index -> item(Integer.toString(index))).toList();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(publishers)) {
            var tasks = new ArrayList<Future<?>>();
            for (int publisher = 0; publisher < publishers; publisher++) {
                var batch = items.subList(publisher * perPublisher, (publisher + 1) * perPublisher);
                tasks.add(pool.submit(() -> {
                    await(start);
                    batch.forEach(output::publish);
                }));
            }
            start.countDown();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }

        if (!running) {
            assertThat(received).isEmpty();
            assertThat(transport.pending(ADDRESS)).containsExactlyInAnyOrderElementsOf(items);
            input.start();
        }
        assertThat(transport.pending(ADDRESS)).isEmpty();
        assertThat(received).containsExactlyInAnyOrderElementsOf(items);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void removalInvalidatesBothHandlesEvenWhenTheAddressIsReused(boolean recreate) {
        var transport = new InMemoryWorkTransport();
        transport.create(ADDRESS);
        var oldInput = transport.input(ADDRESS);
        var oldOutput = transport.output(ADDRESS);
        var received = new ArrayList<WorkItem>();
        oldInput.register(handler(received::add));
        oldOutput.publish(item("old pending"));
        transport.remove(ADDRESS);
        assertThat(transport.exists(ADDRESS)).isFalse();

        if (recreate) {
            transport.create(ADDRESS);
            assertThat(transport.pending(ADDRESS)).isEmpty();
            var input = transport.input(ADDRESS);
            input.register(handler(received::add));
            input.start();
        }

        assertThatThrownBy(oldInput::start).isInstanceOf(IllegalStateException.class)
            .hasMessage("Resource was removed: " + ADDRESS);
        assertThatThrownBy(oldInput::stop).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(oldInput::state).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> oldInput.register(handler(received::add))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> oldOutput.publish(item("stale output"))).isInstanceOf(IllegalStateException.class);
        assertThat(received).isEmpty();

        if (recreate) {
            var fresh = item("fresh");
            transport.output(ADDRESS).publish(fresh);
            assertThat(received).containsExactly(fresh);
        }
    }

    @Test
    void stopAndRemovalDoNotWaitForAdmittedWorkAndDiscardOnlyPendingItems() throws Exception {
        var transport = new InMemoryWorkTransport();
        transport.create(ADDRESS);
        var input = transport.input(ADDRESS);
        var output = transport.output(ADDRESS);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new ConcurrentLinkedQueue<WorkItem>();
        input.register(handler(item -> {
            entered.countDown();
            await(release);
            received.add(item);
        }));
        input.start();
        var admitted = item("admitted");
        var pending = item("pending");

        try (var pool = Executors.newFixedThreadPool(2)) {
            var dispatch = pool.submit(() -> output.publish(admitted));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                pool.submit(() -> {
                    input.stop();
                    output.publish(pending);
                    assertThat(transport.pending(ADDRESS)).containsExactly(pending);
                    transport.remove(ADDRESS);
                }).get(5, TimeUnit.SECONDS);
                assertThat(transport.exists(ADDRESS)).isFalse();
                assertThat(received).isEmpty();
            } finally {
                release.countDown();
            }
            dispatch.get(5, TimeUnit.SECONDS);
        }
        assertThat(received).containsExactly(admitted);
        assertThatThrownBy(input::start).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unacceptedDeliveryRemainsPendingWithoutRetryUntilRestart() {
        var transport = new InMemoryWorkTransport();
        transport.create(ADDRESS);
        var input = transport.input(ADDRESS);
        var accepting = new AtomicBoolean();
        var attempts = new AtomicInteger();
        var received = new ArrayList<WorkItem>();
        input.register(handler(item -> {
            attempts.incrementAndGet();
            if (!accepting.get()) {
                input.stop();
                throw new WorkNotAcceptedException("Admission paused");
            }
            received.add(item);
        }));
        input.start();
        var waiting = item("waiting");
        transport.output(ADDRESS).publish(waiting);
        assertThat(attempts).hasValue(1);
        assertThat(received).isEmpty();
        assertThat(transport.pending(ADDRESS)).containsExactly(waiting);

        accepting.set(true);
        input.start();
        assertThat(attempts).hasValue(2);
        assertThat(received).containsExactly(waiting);
        assertThat(transport.pending(ADDRESS)).isEmpty();
    }

    private static WorkItem item(String body) {
        return WorkItem.text(INFO, body).build();
    }

    private static WorkDeliveryHandler handler(Consumer<WorkItem> consumer) {
        return new WorkDeliveryHandler() {
            @Override public void onWork(WorkItem item) { consumer.accept(item); }
            @Override public void onDecodeFailure(byte[] body, Exception failure) { throw new AssertionError(failure); }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
