package io.pockethive.requesttemplates;

/** Explicit location and identity of one HTTP template. */
public record HttpTemplateReference(String templateRoot, String serviceId, String callId) {
  public HttpTemplateReference {
    templateRoot = requireText(templateRoot, "templateRoot");
    serviceId = requireText(serviceId, "serviceId");
    callId = requireText(callId, "callId");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
