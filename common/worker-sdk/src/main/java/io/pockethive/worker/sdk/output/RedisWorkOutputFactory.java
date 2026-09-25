package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.transport.WorkOutput;

import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.RedisPushSupport;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.templating.api.TemplateRenderer;
import org.springframework.core.Ordered;

/**
 * Responsibility: construct the selected Redis output with its configured dependencies.
 * Must not: turn capture into business output or independently reimplement the shared Redis push operation.
 * Contract: RESP-WORK-REDIS-PUSH — docs/architecture/runtime-responsibilities.md#resp-work-redis-push.
 */
public final class RedisWorkOutputFactory implements AutoCloseable, WorkOutputFactory, Ordered {

    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final RedisPushSupport pushSupport;

    public RedisWorkOutputFactory(WorkerControlPlaneRuntime controlPlaneRuntime, TemplateRenderer templateRenderer) {
        this.controlPlaneRuntime = controlPlaneRuntime;
        this.pushSupport = new RedisPushSupport(templateRenderer);
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.outputType() == WorkerOutputType.REDIS;
    }

    @Override
    public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
        if (!(config instanceof RedisOutputProperties properties)) {
            throw new IllegalStateException("Redis outputs require RedisOutputProperties configuration");
        }
        return new RedisWorkOutput(definition, controlPlaneRuntime, properties, pushSupport);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
    @Override public void close() { pushSupport.close(); }
}
