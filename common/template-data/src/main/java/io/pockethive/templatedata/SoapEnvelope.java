package io.pockethive.templatedata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Responsibility: validate SOAP envelopes, reject Faults and derive version-owned namespaces and headers.
 * Must not: execute HTTP, retry calls, select response values or decide product completion.
 * Contract: RESP-TEMPLATE-SOAP — docs/architecture/runtime-responsibilities.md#resp-template-soap.
 */
public final class SoapEnvelope {
    private static final String SOAP_PREFIX = "soap";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String SOAP_ACTION = "SOAPAction";
    private static final int MAX_ACTION_LENGTH = 2048;
    private final XmlDocumentReader reader = new XmlDocumentReader();

    public XmlDocumentView read(SoapVersion version, String document) {
        requireVersion(version);
        XmlDocumentView view = reader.read(document);
        Element envelope = view.document().getDocumentElement();
        if (!named(envelope, version, "Envelope")) throw invalidEnvelope();
        List<Element> children = elements(envelope);
        int bodyIndex = children.size() == 2 && named(children.getFirst(), version, "Header") ? 1 : 0;
        if (children.size() != bodyIndex + 1 || !named(children.get(bodyIndex), version, "Body")) {
            throw invalidEnvelope();
        }
        for (Element child : elements(children.get(bodyIndex))) {
            if ("Fault".equals(child.getLocalName()) && (SoapVersion.SOAP11.namespace().equals(child.getNamespaceURI())
                    || SoapVersion.SOAP12.namespace().equals(child.getNamespaceURI()))) {
                throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "SOAP response contains a Fault.");
            }
        }
        return view;
    }

    public Map<String, String> namespaces(SoapVersion version, Map<String, String> supplied) {
        requireVersion(version);
        new XPathNamespaces(supplied);
        if (supplied.keySet().stream().anyMatch(SOAP_PREFIX::equalsIgnoreCase)) {
            throw new TemplateDataException(TemplateDataError.INVALID_SELECTOR, "SOAP namespace prefix is reserved.");
        }
        Map<String, String> result = new LinkedHashMap<>(supplied);
        result.put(SOAP_PREFIX, version.namespace());
        return Map.copyOf(result);
    }

    public Map<String, String> headers(SoapVersion version, String action) {
        requireVersion(version);
        if (action == null || action.length() > MAX_ACTION_LENGTH
                || action.chars().anyMatch(character -> character < 32 || character > 126 || character == '"' || character == '\\')) {
            throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "Invalid SOAP action.");
        }
        return switch (version) {
            case SOAP11 -> Map.of(CONTENT_TYPE, "text/xml; charset=utf-8", SOAP_ACTION, "\"" + action + "\"");
            case SOAP12 -> Map.of(CONTENT_TYPE, "application/soap+xml; charset=utf-8; action=\"" + action + "\"");
        };
    }

    private boolean named(Element element, SoapVersion version, String localName) {
        return element != null && version.namespace().equals(element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }

    private List<Element> elements(Element parent) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) result.add(element);
            else if ((child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !child.getTextContent().isBlank()) throw invalidEnvelope();
        }
        return result;
    }

    private void requireVersion(SoapVersion version) {
        if (version == null) throw new TemplateDataException(TemplateDataError.INVALID_VALUE, "An explicit SOAP version is required.");
    }

    private TemplateDataException invalidEnvelope() {
        return new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "SOAP requires a matching Envelope and one Body after an optional Header, without unexpected text.");
    }
}
