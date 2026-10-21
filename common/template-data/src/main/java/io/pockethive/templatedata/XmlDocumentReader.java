package io.pockethive.templatedata;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Responsibility: decode bounded XML without DTDs, entities, external resources or XInclude.
 * Must not: expose mutable DOM publicly or interpret SOAP and selectors.
 * Contract: RESP-TEMPLATE-DATA — docs/architecture/runtime-responsibilities.md#resp-template-data.
 */
public final class XmlDocumentReader {
    private static final String XINCLUDE_NAMESPACE = "http://www.w3.org/2001/XInclude";
    private static final String DEPTH_LIMIT_ERROR = "JAXP00010006";
    private static final int MAX_DEPTH = 256;

    public XmlDocumentView read(String document) {
        DocumentLimits.requireSize(document);
        try {
            Document parsed = builder().parse(new InputSource(new StringReader(document)));
            if (parsed.getElementsByTagNameNS(XINCLUDE_NAMESPACE, "*").getLength() != 0) {
                throw new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "XML must not contain XInclude elements.");
            }
            return new XmlDocumentView(parsed);
        } catch (TemplateDataException failure) {
            throw failure;
        } catch (SAXParseException failure) {
            if (failure.getMessage() != null && failure.getMessage().contains(DEPTH_LIMIT_ERROR)) {
                throw new TemplateDataException(TemplateDataError.LIMIT_EXCEEDED, "XML exceeds the 256-element depth limit.");
            }
            throw invalidDocument();
        } catch (Exception failure) {
            throw invalidDocument();
        }
    }

    private DocumentBuilder builder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", Integer.toString(MAX_DEPTH));
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://apache.org/xml/features/dom/defer-node-expansion", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resources are forbidden."); });
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException failure) throws SAXException { throw failure; }
            @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
        });
        return builder;
    }

    private TemplateDataException invalidDocument() {
        return new TemplateDataException(TemplateDataError.INVALID_DOCUMENT, "Document is not safe, well-formed XML.");
    }
}
