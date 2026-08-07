package io.pockethive.requesttemplates;

import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.templating.MessageBodyType;
import io.pockethive.worker.sdk.templating.MessageTemplate;
import io.pockethive.worker.sdk.templating.MessageTemplateRenderer;
import io.pockethive.templating.TemplateRenderer;
import java.util.Objects;

/**
 * Renders the canonical HTTP template contract into its concrete request values.
 *
 * <p>Authentication remains an explicit concern of the calling adapter. This renderer never
 * applies, drops, or synthesizes an {@code authRef}.</p>
 */
public final class HttpTemplateRenderer {

  private final MessageTemplateRenderer messageTemplateRenderer;

  public HttpTemplateRenderer(TemplateRenderer templateRenderer) {
    this.messageTemplateRenderer = new MessageTemplateRenderer(Objects.requireNonNull(templateRenderer, "templateRenderer"));
  }

  public RenderedHttpRequest render(HttpTemplateDefinition definition, WorkItem invocation) {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(invocation, "invocation");
    if (!"HTTP".equals(definition.protocol())) {
      throw new IllegalArgumentException("HttpTemplateRenderer requires an HTTP template");
    }

    MessageTemplate template = MessageTemplate.builder()
        .bodyType(MessageBodyType.HTTP)
        .pathTemplate(definition.pathTemplate())
        .methodTemplate(definition.method())
        .bodyTemplate(definition.bodyTemplate())
        .headerTemplates(definition.headersTemplate())
        .build();
    MessageTemplateRenderer.RenderedMessage rendered = messageTemplateRenderer.render(template, invocation);
    HttpRequestEnvelope.HttpRequest request = new HttpRequestEnvelope.HttpRequest(
        rendered.method(), rendered.path(), rendered.headers(), rendered.body());
    return new RenderedHttpRequest(
        request.method(), request.path(), request.headers(), rendered.body(), definition.resultRules());
  }
}
