package io.pockethive.worker.sdk.input.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RedisDataSetWorkInputTest {

    private RedisDataSetWorkInput input;

    @AfterEach
    void tearDown() throws Exception {
        if (input != null) {
            input.stop();
        }
    }

    @Test
    void dispatchesUntilDatasetExhausted() throws Exception {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(5.0);
        properties.setInitialDelayMs(10_000L);
        properties.setTickIntervalMs(1_000L);

        Queue<String> data = new ArrayDeque<>(List.of("one", "two", "three"));
        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();

        WorkerControlPlaneRuntime controlPlane = mockControlPlane();
        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new QueueRedisClientFactory(data)
        );

        input.start();
        input.tick();

        assertThat(runtime.items).hasSize(3);
        assertThat(runtime.items.stream().map(WorkItem::asString)).containsExactly("one", "two", "three");
    }

    @Test
    void noDispatchWhenDatasetEmpty() throws Exception {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setRatePerSec(2.0);
        properties.setInitialDelayMs(10_000L);
        properties.setTickIntervalMs(1_000L);

        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();

        WorkerControlPlaneRuntime controlPlane = mockControlPlane();
        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new QueueRedisClientFactory(new ArrayDeque<>())
        );

        input.start();
        input.tick();

        assertThat(runtime.items).isEmpty();
    }

    @Test
    void startsAndWaitsForConfigWhenSourceModeMissing() {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setListName(null);

        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();
        WorkerControlPlaneRuntime controlPlane = mockControlPlane();

        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new QueueRedisClientFactory(new ArrayDeque<>())
        );

        input.start();
        input.tick();

        assertThat(runtime.items).isEmpty();
    }

    @Test
    void parsesSourcesJsonFromBootstrapConfig() throws Exception {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setListName(null);
        properties.setSourcesJson("""
            [
              {"listName":"red","weight":3},
              {"listName":"bal","weight":1}
            ]
            """);
        properties.setPickStrategy(RedisDataSetInputProperties.PickStrategy.ROUND_ROBIN);
        properties.setRatePerSec(2.0);
        properties.setInitialDelayMs(10_000L);
        properties.setTickIntervalMs(1_000L);

        Map<String, Queue<String>> sourceQueues = new LinkedHashMap<>();
        sourceQueues.put("red", new ArrayDeque<>(List.of("r1")));
        sourceQueues.put("bal", new ArrayDeque<>(List.of("b1")));

        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();
        WorkerControlPlaneRuntime controlPlane = mockControlPlane();

        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new MultiQueueRedisClientFactory(sourceQueues),
            () -> 0.0
        );

        input.start();
        input.tick();

        assertThat(runtime.items).hasSize(2);
        assertThat(runtime.items.stream().map(WorkItem::asString)).containsExactly("r1", "b1");
    }

    @Test
    void dispatchesAcrossMultipleSourcesInRoundRobinOrder() throws Exception {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setListName(null);
        properties.setSources(List.of(source("red", 1.0), source("bal", 1.0)));
        properties.setPickStrategy(RedisDataSetInputProperties.PickStrategy.ROUND_ROBIN);
        properties.setRatePerSec(3.0);
        properties.setInitialDelayMs(10_000L);
        properties.setTickIntervalMs(1_000L);

        Map<String, Queue<String>> sourceQueues = new LinkedHashMap<>();
        sourceQueues.put("red", new ArrayDeque<>(List.of("r1", "r2")));
        sourceQueues.put("bal", new ArrayDeque<>(List.of("b1")));

        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();
        WorkerControlPlaneRuntime controlPlane = mockControlPlane();

        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new MultiQueueRedisClientFactory(sourceQueues),
            () -> 0.0
        );

        input.start();
        input.tick();

        assertThat(runtime.items).hasSize(3);
        assertThat(runtime.items.stream().map(WorkItem::asString)).containsExactly("r1", "b1", "r2");
        assertThat(runtime.items.stream().map(i -> i.headers().get("x-ph-redis-list"))).containsExactly("red", "bal", "red");
    }

    @Test
    void weightedPickRetriesAnotherSourceWhenChosenOneIsEmpty() throws Exception {
        RedisDataSetInputProperties properties = baseProperties();
        properties.setListName(null);
        properties.setSources(List.of(source("red", 1.0), source("bal", 1.0)));
        properties.setPickStrategy(RedisDataSetInputProperties.PickStrategy.WEIGHTED_RANDOM);
        properties.setRatePerSec(1.0);
        properties.setInitialDelayMs(10_000L);
        properties.setTickIntervalMs(1_000L);

        Map<String, Queue<String>> sourceQueues = new LinkedHashMap<>();
        sourceQueues.put("red", new ArrayDeque<>());
        sourceQueues.put("bal", new ArrayDeque<>(List.of("b1")));

        RecordingWorkerRuntime runtime = new RecordingWorkerRuntime();
        WorkerDefinition definition = definition();
        WorkerControlPlaneRuntime controlPlane = mockControlPlane();

        input = new RedisDataSetWorkInput(
            definition,
            controlPlane,
            runtime,
            identity(),
            properties,
            LoggerFactory.getLogger("test-redis-input"),
            new MultiQueueRedisClientFactory(sourceQueues),
            () -> 0.0
        );

        input.start();
        input.tick();

        assertThat(runtime.items).hasSize(1);
        assertThat(runtime.items.getFirst().asString()).isEqualTo("b1");
        assertThat(runtime.items.getFirst().headers().get("x-ph-redis-list")).isEqualTo("bal");
    }

    private static WorkerDefinition definition() {
        return new WorkerDefinition(
            "redisWorker",
            Object.class,
            WorkerInputType.REDIS_DATASET,
            "test-role",
            WorkIoBindings.none(),
            Void.class,
            RedisDataSetInputProperties.class,
            WorkOutputConfig.class,
            WorkerOutputType.NONE,
            "Test redis dataset worker",
            Set.of()
        );
    }

    private static ControlPlaneIdentity identity() {
        return new ControlPlaneIdentity("swarm-1", "role", "instance-1");
    }

    private static WorkerControlPlaneRuntime mockControlPlane() {
        WorkerControlPlaneRuntime runtime = mock(WorkerControlPlaneRuntime.class);
        doNothing().when(runtime).registerStateListener(any(), any());
        doNothing().when(runtime).emitStatusSnapshot();
        return runtime;
    }

    private static RedisDataSetInputProperties baseProperties() {
        RedisDataSetInputProperties properties = new RedisDataSetInputProperties();
        properties.setHost("localhost");
        properties.setListName("dataset");
        properties.setEnabled(true);
        return properties;
    }

    private static RedisDataSetInputProperties.Source source(String listName, double weight) {
        RedisDataSetInputProperties.Source source = new RedisDataSetInputProperties.Source();
        source.setListName(listName);
        source.setWeight(weight);
        return source;
    }

    private static final class QueueRedisClientFactory implements RedisDataSetWorkInput.RedisClientFactory {

        private final Queue<String> queue;

        private QueueRedisClientFactory(Queue<String> queue) {
            this.queue = queue;
        }

        @Override
        public RedisDataSetWorkInput.RedisListClient create(RedisDataSetInputProperties properties) {
            return new QueueRedisListClient(queue);
        }
    }

    private static final class QueueRedisListClient implements RedisDataSetWorkInput.RedisListClient {

        private final Queue<String> queue;

        private QueueRedisListClient(Queue<String> queue) {
            this.queue = queue;
        }

        @Override
        public String pop(String listName) {
            return queue.poll();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class MultiQueueRedisClientFactory implements RedisDataSetWorkInput.RedisClientFactory {

        private final Map<String, Queue<String>> queues;

        private MultiQueueRedisClientFactory(Map<String, Queue<String>> queues) {
            this.queues = queues;
        }

        @Override
        public RedisDataSetWorkInput.RedisListClient create(RedisDataSetInputProperties properties) {
            return new MultiQueueRedisListClient(queues);
        }
    }

    private static final class MultiQueueRedisListClient implements RedisDataSetWorkInput.RedisListClient {

        private final Map<String, Queue<String>> queues;

        private MultiQueueRedisListClient(Map<String, Queue<String>> queues) {
            this.queues = queues;
        }

        @Override
        public String pop(String listName) {
            Queue<String> queue = queues.get(listName);
            return queue == null ? null : queue.poll();
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class RecordingWorkerRuntime implements WorkerRuntime {

        private final List<WorkItem> items = new ArrayList<>();

        @Override
        public WorkItem dispatch(String workerBeanName, WorkItem message) {
            items.add(message);
            return message;
        }
    }
}
