package io.pockethive.templating;

import io.pockethive.templatedata.TemplateDataError;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templatedata.JsonDocumentReader;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: bind explicit endpoint, record and parameters to a portable call.
 * Must not: discover SUT configuration, parse WorkItem envelopes or perform IO.
 * Contract: RESP-PORTABLE-HTTP — docs/architecture/runtime-responsibilities.md#resp-portable-http.
 */
public record PortableHttpBinding(URI endpoint, Map<String, ?> record, Map<String, ?> params) {
    public PortableHttpBinding {
        if (endpoint == null || !Set.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
            || endpoint.getUserInfo() != null || endpoint.getFragment() != null || record == null || params == null) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Explicit HTTP endpoint, record and params are required.");
        }
        new JsonDocumentReader().tree(record);
        new JsonDocumentReader().tree(params);
        record = Collections.unmodifiableMap(new LinkedHashMap<>(record));
        params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
    Map<String, Object> context() {
        String path = endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
        if (endpoint.getRawQuery() != null) path += "?" + endpoint.getRawQuery();
        return Map.of("record", record, "params", params, "endpoint", Map.of("path", path));
    }
}
