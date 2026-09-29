package io.pockethive.worker.sdk.input.redis;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.redis.api.RedisListClients;
import io.pockethive.worker.sdk.config.RedisDataSetInputProperties;
import io.pockethive.work.config.binding.WorkInputConfig;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

/**
 * Responsibility: compose Redis dataset inputs with the selected worker runtime and Redis list API.
 * Must not: create Redis clients, resolve settings or own intake state.
 * Contract: RESP-WORK-REDIS-DATASET — docs/architecture/runtime-responsibilities.md#resp-work-redis-dataset.
 */
public final class RedisDataSetWorkInputFactory implements WorkInputFactory, Ordered {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;

    public RedisDataSetWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity
    ) {
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.REDIS_DATASET;
    }

    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        if (!(config instanceof RedisDataSetInputProperties properties)) {
            throw new IllegalStateException("Redis dataset inputs require RedisDataSetInputProperties configuration");
        }
        return new RedisDataSetWorkInput(definition, controlPlaneRuntime, workerRuntime, identity, properties, logger, RedisListClients::reader);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
