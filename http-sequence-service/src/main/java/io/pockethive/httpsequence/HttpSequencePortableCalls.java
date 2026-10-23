package io.pockethive.httpsequence;

import io.pockethive.requesttemplates.HttpSequencePortablePolicy;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.PortableResponseExtractor;
import io.pockethive.templating.PortableHttpBinding;
import io.pockethive.templating.PortableHttpRenderer;
import io.pockethive.templating.PortableHttpRequest;
import java.net.URI;
import java.util.Map;

/**
 * Responsibility: admit and adapt portable HTTP calls to the existing sequence runtime.
 * Must not: own document parsing, rendering semantics, transport, retries or journey-state writes.
 * Contract: RESP-HTTP-SEQUENCE-PORTABLE — docs/architecture/runtime-responsibilities.md#resp-http-sequence-portable.
 */
final class HttpSequencePortableCalls {
    private final PortableHttpRenderer renderer = new PortableHttpRenderer();
    private final PortableResponseExtractor responses = new PortableResponseExtractor();

    void validate(HttpSequenceWorkerConfig.Step step, PortableHttpCall call) {
        HttpSequencePortablePolicy.validate(step.retry().maxAttempts(), step.continueOnNon2xx(), step.extracts());
        renderer.validate(call);
    }

    PortableHttpRequest render(PortableHttpCall call, URI endpoint, Map<String, ?> record, Map<String, ?> params) {
        return renderer.render(call, new PortableHttpBinding(endpoint, record, params));
    }

    Map<String, String> extract(PortableHttpCall call, HttpCallResult result) {
        if (result.statusCode() < 0 || result.error() != null) {
            throw new IllegalStateException("Portable HTTP transport failed (status " + result.statusCode() + ")"
                + (result.error() == null ? "" : ": " + result.error()));
        }
        return responses.extract(call, result.statusCode(), result.body());
    }
}
