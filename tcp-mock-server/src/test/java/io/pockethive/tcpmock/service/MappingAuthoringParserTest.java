package io.pockethive.tcpmock.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonMappingException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class MappingAuthoringParserTest {
    private final MappingAuthoringParser parser = new MappingAuthoringParser();

    @Test
    void acceptsBothExistingDocumentFormatsAndRetainsMappingSettings() throws Exception {
        for (String body : new String[]{
            "{\"id\":\"sample\",\"priority\":7,\"enabled\":false,\"fixedDelayMs\":125}",
            "id: sample\npriority: 7\nenabled: false\nfixedDelayMs: 125\n"
        }) {
            var mapping = parser.readMapping(parser.readDocument(body));
            assertEquals("sample", mapping.getId());
            assertEquals(7, mapping.getPriority());
            assertFalse(mapping.isEnabled());
            assertEquals(125, mapping.getFixedDelayMs());
        }
    }

    @Test
    void leavesItemBindingUntilEachEntryIsRequested() throws Exception {
        var document = parser.readDocument("[{\"id\":\"good\"},{\"unknownField\":true}]");
        assertTrue(document.isArray());
        assertEquals("good", parser.readMapping(document.get(0)).getId());
        assertThrows(JsonMappingException.class, () -> parser.readMapping(document.get(1)));
    }

    @Test
    void rejectsMalformedDocumentAfterBothDecodersFail() {
        assertThrows(IOException.class, () -> parser.readDocument("["));
    }
}
