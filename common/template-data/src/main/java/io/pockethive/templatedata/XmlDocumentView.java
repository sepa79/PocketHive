package io.pockethive.templatedata;

import org.w3c.dom.Document;

/**
 * Responsibility: hold a parsed XML document behind a read-only public boundary.
 * Must not: expose a publicly mutable DOM or interpret SOAP semantics.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class XmlDocumentView {
    private final Document document;

    XmlDocumentView(Document document) {
        this.document = document;
    }

    Document document() {
        return document;
    }
}
