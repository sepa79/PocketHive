package io.pockethive.requesttemplates;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.Map;

/**
 * Responsibility: decode request-template JSON/YAML with one runtime/authoring policy.
 * Must not: read files, select protocols or validate request-template semantics.
 * Contract: RESP-REQUEST-TEMPLATE-PARSE — docs/architecture/runtime-responsibilities.md#resp-request-template-parse.
 */
public final class RequestTemplateDocumentReader {
    private final ObjectMapper json = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    // Retain the existing YAML first-document policy; duplicate keys are errors.
    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    public Map<String, Object> readJson(byte[] source) throws IOException {
        return json.readValue(source, new TypeReference<>() { });
    }

    public Map<String, Object> readYaml(byte[] source) throws IOException {
        return yaml.readValue(source, new TypeReference<>() { });
    }
}
