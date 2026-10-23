package io.pockethive.requesttemplates;

import java.util.Map;

/**
 * Responsibility: carry a validated response interpretation.
 * Must not: read HTTP or decide workflow completion.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public record PortableResponseDefinition(PortableResponseKind kind, String soapAction, int expectedStatus,
                                         Map<String, String> extract, Map<String, String> namespaces) {
    public PortableResponseDefinition { extract = Map.copyOf(extract); namespaces = Map.copyOf(namespaces); }
}
