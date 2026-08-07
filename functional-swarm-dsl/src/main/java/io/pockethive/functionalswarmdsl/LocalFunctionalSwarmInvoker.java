package io.pockethive.functionalswarmdsl;

import io.pockethive.functionalswarm.contracts.FunctionalSwarmInvocation;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmResponse;
import io.pockethive.functionalswarm.contracts.FunctionalSwarmWorkItemFactory;
import io.pockethive.requestexecution.HttpExecutionRequest;
import io.pockethive.requestexecution.HttpExecutionResult;
import io.pockethive.requestexecution.HttpTargetResolver;
import io.pockethive.requestexecution.RequestExecutor;
import io.pockethive.requesttemplates.HttpTemplateDefinition;
import io.pockethive.requesttemplates.HttpTemplateRenderer;
import io.pockethive.requesttemplates.HttpTemplateResolver;
import io.pockethive.requesttemplates.RenderedHttpRequest;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.util.Objects;

/** Local adapter using the canonical template renderer and shared HTTP executor. */
final class LocalFunctionalSwarmInvoker implements FunctionalSwarmInvoker {
  private static final WorkerInfo LOCAL_RENDER_SOURCE =
      new WorkerInfo("functional-swarm-dsl", "local", "client", null, null);

  private final FunctionalSwarmLocalConfig config;
  private final RequestExecutor requestExecutor;
  private final HttpTemplateDefinition template;
  private final HttpTemplateRenderer templateRenderer;

  LocalFunctionalSwarmInvoker(FunctionalSwarmLocalConfig config, RequestExecutor requestExecutor) {
    this(config, requestExecutor, new HttpTemplateResolver(), new HttpTemplateRenderer(new PebbleTemplateRenderer()));
  }

  LocalFunctionalSwarmInvoker(
      FunctionalSwarmLocalConfig config,
      RequestExecutor requestExecutor,
      HttpTemplateResolver templateResolver,
      HttpTemplateRenderer templateRenderer
  ) {
    this.config = Objects.requireNonNull(config, "config");
    this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    this.template = Objects.requireNonNull(templateResolver, "templateResolver").resolve(config.template());
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    rejectUnsupportedAuthentication(template);
  }

  @Override
  public FunctionalSwarmResponse invoke(FunctionalSwarmInvocation invocation) {
    try {
      RenderedHttpRequest rendered = templateRenderer.render(
          template, FunctionalSwarmWorkItemFactory.create(invocation, LOCAL_RENDER_SOURCE));
      HttpExecutionResult result = requestExecutor.execute(new HttpExecutionRequest(
          rendered.method(), HttpTargetResolver.resolve(config.targetBaseUri(), rendered.path()),
          rendered.headers(), rendered.body()));
      return new FunctionalSwarmResponse(result.statusCode(), result.headers(), result.body());
    } catch (Exception ex) {
      throw new IllegalStateException("Local Functional Swarm invocation failed", ex);
    }
  }

  static void rejectUnsupportedAuthentication(HttpTemplateDefinition definition) {
    if (definition.authRef() != null) {
      throw new IllegalArgumentException("Functional Swarm DSL v1 does not support templates with authRef");
    }
  }
}
