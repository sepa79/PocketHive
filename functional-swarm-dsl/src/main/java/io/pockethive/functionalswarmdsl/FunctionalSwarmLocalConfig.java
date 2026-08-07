package io.pockethive.functionalswarmdsl;

import io.pockethive.requesttemplates.HttpTemplateReference;
import java.net.URI;
import java.util.Objects;

/** Explicit local target and shared template reference. */
public record FunctionalSwarmLocalConfig(HttpTemplateReference template, URI targetBaseUri) {
  public FunctionalSwarmLocalConfig {
    template = Objects.requireNonNull(template, "template");
    targetBaseUri = Objects.requireNonNull(targetBaseUri, "targetBaseUri");
  }
}
