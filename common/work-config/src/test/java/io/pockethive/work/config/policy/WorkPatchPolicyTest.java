package io.pockethive.work.config.policy;

import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.WorkerOutputType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkPatchPolicyTest {

    @Test
    void rejectsExplicitNullRatesForEveryRateDrivenInput() {
        for (var type : List.of(WorkerInputType.SCHEDULER, WorkerInputType.REDIS_DATASET, WorkerInputType.CSV_DATASET)) {
            var previous = Map.<String, Object>of("inputs", Map.of("type", type.name(),
                type.settingsKey(), Map.of("ratePerSec", 1.0)), "outputs", Map.of("type", "NONE"));
            var fields = new java.util.LinkedHashMap<String, Object>();
            fields.put("ratePerSec", null);
            var patch = Map.<String, Object>of("inputs", Map.of(type.settingsKey(), fields));
            assertThatThrownBy(() -> policy(type, WorkerOutputType.NONE).validate(previous, patch, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("finite number >= 0.0");
        }
    }

    @Test
    void allowsBootstrapIoConfigWhenPreviousRawConfigIsEmpty() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.REDIS);

        assertThatCode(() -> policy.validate(Map.of(), redisIoConfig(), false))
            .doesNotThrowAnyException();
    }

    @Test
    void allowsSafeRedisDatasetRateUpdate() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = Map.of("inputs", Map.of("redis", Map.of("ratePerSec", 2500.5)));

        assertThatCode(() -> policy.validate(previous, update, false))
            .doesNotThrowAnyException();
    }

    @Test
    void allowsFullRedisDatasetFormWhenOnlySafeFieldChanges() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = redisInputConfig(2.5);

        assertThatCode(() -> policy.validate(previous, update, false))
            .doesNotThrowAnyException();
    }

    @Test
    void allowsRedisDatasetListNameUpdateWhenWorkerIsDisabledInSingleSourceMode() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = Map.of("inputs", Map.of("redis", Map.of("listName", "ph:other")));

        assertThatCode(() -> policy.validate(previous, update, false))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsRedisDatasetListNameUpdateWhenWorkerIsEnabled() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = Map.of("inputs", Map.of("redis", Map.of("listName", "ph:other")));

        assertThatThrownBy(() -> policy.validate(previous, update, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inputs.redis.listName")
            .hasMessageContaining("enabled worker")
            .hasMessageContaining("stop the swarm first");
    }

    @Test
    void allowsUnchangedRedisDatasetListNameInFullFormWhileWorkerIsEnabled() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = redisInputConfig(2.5);

        assertThatCode(() -> policy.validate(previous, update, true))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsRedisDatasetListNameUpdateInMultiSourceMode() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "REDIS_DATASET",
                "redis", Map.of(
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "listName", "",
                    "sources", List.of(Map.of("listName", "ph:dataset", "weight", 1.0)),
                    "pickStrategy", "ROUND_ROBIN",
                    "ratePerSec", 1.0
                )
            )
        );
        Map<String, Object> update = Map.of("inputs", Map.of("redis", Map.of("listName", "ph:other")));

        assertThatThrownBy(() -> policy.validate(previous, update, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inputs.redis.listName")
            .hasMessageContaining("single-source listName mode");
    }

    @Test
    void rejectsInvalidRedisDatasetListNameUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);

        for (Object invalidName : List.of(7, "{{ 'red' }}")) {
            assertInvalid(policy, previous, Map.of("inputs", Map.of("redis", Map.of("listName", invalidName))),
                "inputs.redis.listName");
        }

        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("redis", Map.of("listName", " "))),
            "inputs.redis.listName"
        );
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("redis", Map.of("listName", " ph:other "))),
            "inputs.redis.listName"
        );
    }

    @Test
    void rejectsUnsafeRedisDatasetEndpointUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);

        assertUnsafe(policy, previous, Map.of("inputs", Map.of("redis", Map.of("port", 6380))), "inputs.redis.port");
    }

    @Test
    void rejectsInvalidRedisDatasetOperationalRateUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);

        assertInvalid(policy, previous, Map.of("inputs", Map.of("redis", Map.of("ratePerSec", "fast"))), "inputs.redis.ratePerSec");
        assertInvalid(policy, previous, Map.of("inputs", Map.of("redis", Map.of("ratePerSec", -0.1))), "inputs.redis.ratePerSec");
        assertThatCode(() -> policy.validate(
            Map.of(),
            Map.of("inputs", Map.of("redis", Map.of("ratePerSec", 2500.5))),
            false
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeCsvDatasetSourceUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.CSV_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "CSV_DATASET",
                "csv", Map.of(
                    "filePath", "/app/scenario/users.csv",
                    "ratePerSec", 1.0,
                    "rotate", false,
                    "skipHeader", true,
                    "delimiter", ",",
                    "charset", "UTF-8",
                    "startupDelaySeconds", 0,
                    "tickIntervalMs", 1000
                )
            )
        );

        assertUnsafe(
            policy,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("filePath", "/app/scenario/other.csv"))),
            "inputs.csv.filePath"
        );
        assertThatCode(() -> policy.validate(
            previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", 2500.5))),
            false
        )).doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", "3.0"))), false))
            .doesNotThrowAnyException();
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", -0.1))),
            "inputs.csv.ratePerSec"
        );
    }

    @Test
    void rejectsUnsafeRedisOutputUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.RABBITMQ, WorkerOutputType.REDIS);
        Map<String, Object> previous = redisOutputConfig();

        assertUnsafe(
            policy,
            previous,
            Map.of("outputs", Map.of("redis", Map.of("port", 6380))),
            "outputs.redis.port"
        );
        assertUnsafe(
            policy,
            previous,
            Map.of("outputs", Map.of("redis", Map.of("routes", List.of(Map.of(
                "header", "x-ph-flow",
                "headerMatch", "^BAL$",
                "list", "ph:balance"
            ))))),
            "outputs.redis.routes"
        );
    }

    @Test
    void rejectsLiveResetWhenPreviousConfigContainsIoBlocks() {
        WorkPatchPolicy policy = policy(WorkerInputType.REDIS_DATASET, WorkerOutputType.REDIS);

        assertThatThrownBy(() -> policy.validateReset(redisIoConfig()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inputs")
            .hasMessageContaining("cannot change unsafe IO field");
    }

    @Test
    void allowsSafeSchedulerOperationalUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.SCHEDULER, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 10)
            )
        );
        Map<String, Object> update = Map.of(
            "inputs", Map.of("scheduler", Map.of("ratePerSec", 2500.5, "maxMessages", 250000, "reset", true))
        );

        assertThatCode(() -> policy.validate(previous, update, false))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSchedulerOperationalUpdates() {
        WorkPatchPolicy policy = policy(WorkerInputType.SCHEDULER, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 10)
            )
        );

        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("ratePerSec", Double.POSITIVE_INFINITY))),
            "inputs.scheduler.ratePerSec"
        );
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("ratePerSec", -0.1))),
            "inputs.scheduler.ratePerSec"
        );
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("maxMessages", -1))),
            "inputs.scheduler.maxMessages"
        );
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("maxMessages", 1.5))),
            "inputs.scheduler.maxMessages"
        );
        assertInvalid(
            policy,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("reset", "true"))),
            "inputs.scheduler.reset"
        );
    }

    private static void assertUnsafe(
        WorkPatchPolicy policy,
        Map<String, Object> previous,
        Map<String, Object> update,
        String field
    ) {
        assertThatThrownBy(() -> policy.validate(previous, update, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(field)
            .hasMessageContaining("cannot change unsafe IO field");
    }

    private static void assertInvalid(
        WorkPatchPolicy policy,
        Map<String, Object> previous,
        Map<String, Object> update,
        String field
    ) {
        assertThatThrownBy(() -> policy.validate(previous, update, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(field)
            .hasMessageContaining("invalid operational IO field");
    }

    private static Map<String, Object> redisIoConfig() {
        Map<String, Object> config = new java.util.LinkedHashMap<>(redisInputConfig(1.0));
        config.putAll(redisOutputConfig());
        return Map.copyOf(config);
    }

    private static Map<String, Object> redisInputConfig(double ratePerSec) {
        return Map.of(
            "inputs", Map.of(
                "type", "REDIS_DATASET",
                "redis", Map.of(
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "listName", "ph:dataset",
                    "sources", List.of(),
                    "pickStrategy", "ROUND_ROBIN",
                    "ratePerSec", ratePerSec
                )
            )
        );
    }

    private static Map<String, Object> redisOutputConfig() {
        return Map.of(
            "outputs", Map.of(
                "type", "REDIS",
                "redis", Map.of(
                    "host", "redis",
                    "port", 6379,
                    "ssl", false,
                    "sourceStep", "LAST",
                    "pushDirection", "RPUSH",
                    "routes", List.of(),
                    "targetListTemplate", "",
                    "defaultList", "ph:out",
                    "maxLen", -1
                )
            )
        );
    }

    private static WorkPatchPolicy policy(WorkerInputType input, WorkerOutputType output) {
        return new WorkPatchPolicy("testWorker", input, output);
    }
}
