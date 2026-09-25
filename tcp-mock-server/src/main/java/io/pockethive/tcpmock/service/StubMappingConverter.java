package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.StubMapping;
import org.springframework.stereotype.Component;

/**
 * Responsibility: convert existing stub contract values to/from runtime mappings.
 * Must not: read files, change registry state or add validation/default policies.
 * Contract: RESP-TCP-MOCK-STUB-CONVERSION — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-stub-conversion.
 */
@Component
public class StubMappingConverter {
    public MessageTypeMapping toMapping(StubMapping stub, String description) {
        return new MessageTypeMapping(stub.getId(), stub.getRequest().getBodyPattern(),
            stub.getResponse().getBody(), description);
    }

    public StubMapping toStub(MessageTypeMapping mapping) {
        StubMapping stub = new StubMapping();
        stub.setId(mapping.getId());
        StubMapping.Request request = new StubMapping.Request();
        request.setBodyPattern(mapping.getRequestPattern());
        stub.setRequest(request);
        StubMapping.Response response = new StubMapping.Response();
        response.setBody(mapping.getResponseTemplate());
        stub.setResponse(response);
        return stub;
    }
}
