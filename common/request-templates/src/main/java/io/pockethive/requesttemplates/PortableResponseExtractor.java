package io.pockethive.requesttemplates;

import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.JsonPointerSelector;
import io.pockethive.templatedata.SoapEnvelope;
import io.pockethive.templatedata.SoapVersion;
import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templatedata.XPathSelector;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: interpret a portable call's explicit response using shared data/protocol owners.
 * Must not: execute HTTP, persist records or decide workflow completion/retry.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public final class PortableResponseExtractor {
    public Map<String, String> extract(PortableHttpCall call, int status, String body) {
        new PortableHttpCallParser().validate(call);
        PortableResponseDefinition response = call.response();
        Map<String, String> result = new LinkedHashMap<>();
        if (response.kind() == PortableResponseKind.JSON) {
            var document = new JsonDocumentReader().read(body);
            response.extract().forEach((name, pointer) -> result.put(name, new JsonPointerSelector().requiredScalar(document, pointer)));
        } else {
            SoapEnvelope soap = new SoapEnvelope();
            SoapVersion version = SoapVersion.valueOf(response.kind().name());
            var document = soap.read(version, body);
            var namespaces = soap.namespaces(version, response.namespaces());
            response.extract().forEach((name, selector) -> result.put(name, new XPathSelector().requiredScalar(document, selector, namespaces)));
        }
        if (status != response.expectedStatus()) throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Unexpected HTTP response status.");
        return Map.copyOf(result);
    }
}
