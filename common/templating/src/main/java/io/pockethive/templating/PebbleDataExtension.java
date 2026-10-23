package io.pockethive.templating;

import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Function;
import io.pockethive.requesttemplates.PortableCapability;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: register exactly the declared shared data operations in Pebble.
 * Must not: infer capabilities or enable eval/sequence execution.
 * Contract: RESP-TEMPLATE-RENDER — docs/architecture/runtime-responsibilities.md#resp-template-render.
 */
final class PebbleDataExtension extends AbstractExtension {
    private final Map<String, Function> functions;
    PebbleDataExtension(Set<PortableCapability> capabilities, boolean validationOnly) {
        Map<String, Function> registered = new LinkedHashMap<>();
        for (PortableCapability capability : capabilities) {
            if (capability == PortableCapability.SOAP11 || capability == PortableCapability.SOAP12) continue;
            registered.put(capability.capabilityName(), new PebbleDataFunction(capability, validationOnly));
        }
        functions = Map.copyOf(registered);
    }
    @Override public Map<String, Function> getFunctions() { return functions; }
}
