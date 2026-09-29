package io.pockethive.tcpmock.service;

import io.pockethive.tcpmock.controller.WebController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DocumentationReaderTest {
    @Test
    void servesPackagedDocumentationAndPreservesHttpErrors() {
        var controller = new WebController(null, null, null, null, new DocumentationReader());
        assertEquals(400, controller.getDocumentation("../README.md").getStatusCode().value());
        assertEquals(404, controller.getDocumentation("absent-test-document-719304.md").getStatusCode().value());
        var response = controller.getDocumentation("START-HERE.md");
        assertEquals(200, response.getStatusCode().value());
        assertFalse(response.getBody().isBlank());
        assertEquals("text/markdown; charset=UTF-8", response.getHeaders().getFirst("Content-Type"));
    }
}
