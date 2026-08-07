package io.pockethive.functionalswarmingress;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmJsonCodec;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmProtocol;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmRpcRequest;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmWorkItemFactory;
import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.HttpTemplateRenderer;
import io.pockethive.requesttemplates.HttpTemplateResolver;
import io.pockethive.requesttemplates.RenderedHttpRequest;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.templating.TemplateRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Validates Functional Swarm RPC input and produces the canonical Processor HTTP envelope. */
@Component("functionalSwarmIngressWorker")
@PocketHiveWorker(capabilities = {WorkerCapability.MESSAGE_DRIVEN}, config = FunctionalSwarmIngressWorkerConfig.class)
class FunctionalSwarmIngressWorker implements PocketHiveWorkerFunction {
  private final FunctionalSwarmJsonCodec codec = new FunctionalSwarmJsonCodec();
  private final HttpTemplateResolver templateResolver;
  private final HttpTemplateRenderer templateRenderer;
  private volatile FunctionalSwarmIngressWorkerConfig loadedConfig;
  private volatile HttpTemplateDefinition loadedTemplate;

  @Autowired
  FunctionalSwarmIngressWorker(FunctionalSwarmIngressWorkerProperties properties, TemplateRenderer templateRenderer) {
    this(new HttpTemplateResolver(), new HttpTemplateRenderer(templateRenderer));
  }

  FunctionalSwarmIngressWorker(HttpTemplateResolver templateResolver, HttpTemplateRenderer templateRenderer) {
    this.templateResolver = Objects.requireNonNull(templateResolver, "templateResolver");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
  }

  @Override
  public WorkItem onMessage(WorkItem seed, WorkerContext context) {
    FunctionalSwarmIngressWorkerConfig config = context.requireConfig(FunctionalSwarmIngressWorkerConfig.class);
    FunctionalSwarmRpcRequest request = codec.readRequest(seed.payload());
    requireReplyList(config, request);
    HttpTemplateDefinition template = templateFor(config);
    if (template.authRef() != null) {
      throw new IllegalArgumentException("Functional Swarm DSL v1 does not support templates with authRef");
    }

    RenderedHttpRequest rendered = templateRenderer.render(
        template, FunctionalSwarmWorkItemFactory.create(request.invocation(), context.info()));
    Map<String, Object> headers = new LinkedHashMap<>(request.invocation().headers());
    headers.put(FunctionalSwarmProtocol.REPLY_LIST_HEADER, request.replyList());
    headers.put(FunctionalSwarmProtocol.REQUEST_ID_HEADER, request.requestId());

    WorkItem envelope = WorkItem.json(context.info(), rendered.toEnvelope())
        .headers(headers)
        .contentType("application/json")
        .build();
    WorkStep last = lastStep(envelope);
    return seed.toBuilder()
        .headers(headers)
        .contentType(envelope.contentType())
        .step(context.info(), last.payload(), last.payloadEncoding(), last.headers())
        .build();
  }

  private HttpTemplateDefinition templateFor(FunctionalSwarmIngressWorkerConfig config) {
    if (!config.equals(loadedConfig)) {
      synchronized (this) {
        if (!config.equals(loadedConfig)) {
          loadedTemplate = templateResolver.resolve(config.template());
          loadedConfig = config;
        }
      }
    }
    return Objects.requireNonNull(loadedTemplate, "loadedTemplate");
  }

  private static void requireReplyList(FunctionalSwarmIngressWorkerConfig config, FunctionalSwarmRpcRequest request) {
    String expected = config.replyListPrefix() + request.requestId();
    if (!expected.equals(request.replyList())) {
      throw new IllegalArgumentException("Functional Swarm replyList does not match the configured reply namespace");
    }
  }

  private static WorkStep lastStep(WorkItem item) {
    WorkStep last = null;
    for (WorkStep step : item.steps()) {
      last = step;
    }
    if (last == null) {
      throw new IllegalStateException("Functional Swarm ingress did not produce a work step");
    }
    return last;
  }
}
