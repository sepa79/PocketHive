package io.pockethive.worker.sdk.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LiveIoConfigUpdateGuardTest {

    @Test
    void allowsBootstrapIoConfigWhenPreviousRawConfigIsEmpty() {
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.REDIS);

        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(definition, Map.of(), redisIoConfig()))
            .doesNotThrowAnyException();
    }

    @Test
    void allowsSafeRedisDatasetRateUpdate() {
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = Map.of("inputs", Map.of("redis", Map.of("ratePerSec", 2500.5)));

        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(definition, previous, update))
            .doesNotThrowAnyException();
    }

    @Test
    void allowsFullRedisDatasetFormWhenOnlySafeFieldChanges() {
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);
        Map<String, Object> update = redisInputConfig(2.5);

        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(definition, previous, update))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeRedisDatasetEndpointUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);

        assertUnsafe(definition, previous, Map.of("inputs", Map.of("redis", Map.of("port", 6380))), "inputs.redis.port");
        assertUnsafe(
            definition,
            previous,
            Map.of("inputs", Map.of("redis", Map.of("listName", "ph:other"))),
            "inputs.redis.listName"
        );
    }

    @Test
    void rejectsInvalidRedisDatasetOperationalRateUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.NONE);
        Map<String, Object> previous = redisInputConfig(1.0);

        assertInvalid(definition, previous, Map.of("inputs", Map.of("redis", Map.of("ratePerSec", "fast"))), "inputs.redis.ratePerSec");
        assertInvalid(definition, previous, Map.of("inputs", Map.of("redis", Map.of("ratePerSec", -0.1))), "inputs.redis.ratePerSec");
        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(
            definition,
            Map.of(),
            Map.of("inputs", Map.of("redis", Map.of("ratePerSec", 2500.5)))
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeCsvDatasetSourceUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.CSV_DATASET, WorkerOutputType.NONE);
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
            definition,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("filePath", "/app/scenario/other.csv"))),
            "inputs.csv.filePath"
        );
        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(
            definition,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", 2500.5)))
        )).doesNotThrowAnyException();
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", "3.0"))),
            "inputs.csv.ratePerSec"
        );
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("csv", Map.of("ratePerSec", -0.1))),
            "inputs.csv.ratePerSec"
        );
    }

    @Test
    void rejectsUnsafeRedisOutputUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.RABBITMQ, WorkerOutputType.REDIS);
        Map<String, Object> previous = redisOutputConfig();

        assertUnsafe(
            definition,
            previous,
            Map.of("outputs", Map.of("redis", Map.of("port", 6380))),
            "outputs.redis.port"
        );
        assertUnsafe(
            definition,
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
        WorkerDefinition definition = definition(WorkerInputType.REDIS_DATASET, WorkerOutputType.REDIS);

        assertThatThrownBy(() -> LiveIoConfigUpdateGuard.validateReset(definition, redisIoConfig()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inputs")
            .hasMessageContaining("cannot change unsafe IO field");
    }

    @Test
    void allowsSafeSchedulerOperationalUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.SCHEDULER, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 10)
            )
        );
        Map<String, Object> update = Map.of(
            "inputs", Map.of("scheduler", Map.of("ratePerSec", 2500.5, "maxMessages", 250000, "reset", true))
        );

        assertThatCode(() -> LiveIoConfigUpdateGuard.validate(definition, previous, update))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSchedulerOperationalUpdates() {
        WorkerDefinition definition = definition(WorkerInputType.SCHEDULER, WorkerOutputType.NONE);
        Map<String, Object> previous = Map.of(
            "inputs", Map.of(
                "type", "SCHEDULER",
                "scheduler", Map.of("ratePerSec", 1.0, "maxMessages", 10)
            )
        );

        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("ratePerSec", Double.POSITIVE_INFINITY))),
            "inputs.scheduler.ratePerSec"
        );
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("ratePerSec", -0.1))),
            "inputs.scheduler.ratePerSec"
        );
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("maxMessages", -1))),
            "inputs.scheduler.maxMessages"
        );
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("maxMessages", 1.5))),
            "inputs.scheduler.maxMessages"
        );
        assertInvalid(
            definition,
            previous,
            Map.of("inputs", Map.of("scheduler", Map.of("reset", "true"))),
            "inputs.scheduler.reset"
        );
    }

    private static void assertUnsafe(
        WorkerDefinition definition,
        Map<String, Object> previous,
        Map<String, Object> update,
        String field
    ) {
        assertThatThrownBy(() -> LiveIoConfigUpdateGuard.validate(definition, previous, update))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(field)
            .hasMessageContaining("cannot change unsafe IO field");
    }

    private static void assertInvalid(
        WorkerDefinition definition,
        Map<String, Object> previous,
        Map<String, Object> update,
        String field
    ) {
        assertThatThrownBy(() -> LiveIoConfigUpdateGuard.validate(definition, previous, update))
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

    private static WorkerDefinition definition(WorkerInputType input, WorkerOutputType output) {
        return new WorkerDefinition(
            "testWorker",
            Object.class,
            input,
            "test-role",
            WorkIoBindings.none(),
            Void.class,
            WorkInputConfig.class,
            WorkOutputConfig.class,
            output,
            "test worker",
            Set.of()
        );
    }
}
