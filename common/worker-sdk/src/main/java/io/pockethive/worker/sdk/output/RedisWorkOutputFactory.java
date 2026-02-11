package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.RedisOutputProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.RedisPushSupport;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import org.springframework.core.Ordered;

public final class RedisWorkOutputFactory implements WorkOutputFactory, Ordered {

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
        RedisOutputProperties properties = config instanceof RedisOutputProperties props
            ? props
            : new RedisOutputProperties();
        return new RedisWorkOutput(definition, controlPlaneRuntime, properties, pushSupport);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
