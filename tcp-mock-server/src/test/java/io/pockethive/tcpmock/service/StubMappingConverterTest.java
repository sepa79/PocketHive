package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.StubMapping;
import org.junit.jupiter.api.Test;

class StubMappingConverterTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StubMappingConverter converter = new StubMappingConverter();

    @Test
    void mapsExistingJsonFieldsAndRetainsRuntimeDefaultsAndSourceDescription() throws Exception {
        var stub = json.readValue("{\"id\":\"sample\",\"request\":{\"bodyPattern\":\"^HELLO$\"},\"response\":{\"body\":\"OK\"}}", StubMapping.class);
        var mapping = converter.toMapping(stub, "source description");
        assertEquals("sample", mapping.getId());
        assertEquals("^HELLO$", mapping.getRequestPattern());
        assertEquals("OK", mapping.getResponseTemplate());
        assertEquals("source description", mapping.getDescription());
        var defaults = new MessageTypeMapping();
        assertEquals(defaults.getPriority(), mapping.getPriority());
        assertEquals(defaults.isEnabled(), mapping.isEnabled());
        assertEquals(defaults.getRequestDelimiter(), mapping.getRequestDelimiter());
        assertEquals(defaults.getFixedDelayMs(), mapping.getFixedDelayMs());
    }

    @Test
    void exportsOnlyTheExistingLossyStubShape() throws Exception {
        var mapping = new MessageTypeMapping("sample", "^HELLO$", "OK", "not exported");
        mapping.setPriority(99);
        mapping.setFixedDelayMs(400);
        var actual = json.valueToTree(converter.toStub(mapping));
        assertEquals(json.readTree("{\"id\":\"sample\",\"request\":{\"bodyPattern\":\"^HELLO$\"},\"response\":{\"body\":\"OK\"}}"), actual);
    }

    @Test
    void retainsNullValuesButDoesNotInventMissingNestedObjects() throws Exception {
        var stub = json.readValue("{\"request\":{},\"response\":{}}", StubMapping.class);
        var mapping = converter.toMapping(stub, null);
        assertNull(mapping.getId());
        assertNull(mapping.getRequestPattern());
        assertNull(mapping.getResponseTemplate());
        assertNull(mapping.getDescription());
        assertThrows(NullPointerException.class, () -> converter.toMapping(new StubMapping(), "source"));
    }
}
