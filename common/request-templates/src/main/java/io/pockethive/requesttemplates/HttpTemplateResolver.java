package io.pockethive.requesttemplates;

import java.util.Map;
import java.util.Objects;

/** Resolves one explicit HTTP template reference through the canonical disk loader. */
public final class HttpTemplateResolver {
  private final TemplateLoader loader;

  public HttpTemplateResolver() {
    this(new TemplateLoader());
  }

  public HttpTemplateResolver(TemplateLoader loader) {
    this.loader = Objects.requireNonNull(loader, "loader");
  }

  public HttpTemplateDefinition resolve(HttpTemplateReference reference) {
    Objects.requireNonNull(reference, "reference");
    Map<String, TemplateDefinition> templates = loader.load(reference.templateRoot(), reference.serviceId());
    TemplateDefinition definition = templates.get(TemplateLoader.key(reference.serviceId(), reference.callId()));
    if (!(definition instanceof HttpTemplateDefinition httpDefinition)) {
      throw new IllegalArgumentException(
          "No HTTP template found for serviceId=%s callId=%s".formatted(reference.serviceId(), reference.callId()));
    }
    return httpDefinition;
  }
}
