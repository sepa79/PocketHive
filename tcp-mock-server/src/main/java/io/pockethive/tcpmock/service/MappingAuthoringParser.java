package io.pockethive.tcpmock.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import java.io.IOException;
import org.springframework.stereotype.Component;

/**
 * Responsibility: decode the existing dual-format mapping authoring body and entries.
 * Must not: mutate registry state, write files or decide HTTP outcomes.
 * Contract: RESP-TCP-MOCK-MAPPING-AUTHORING — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-mapping-authoring.
 */
@Component
public class MappingAuthoringParser {
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final YAMLMapper yamlMapper = new YAMLMapper();

    public JsonNode readDocument(String body) throws IOException {
        try {
            return jsonMapper.readTree(body);
        } catch (Exception jsonEx) {
            return yamlMapper.readTree(body);
        }
    }

    public MessageTypeMapping readMapping(JsonNode node) throws IOException {
        return yamlMapper.treeToValue(node, MessageTypeMapping.class);
    }
}
