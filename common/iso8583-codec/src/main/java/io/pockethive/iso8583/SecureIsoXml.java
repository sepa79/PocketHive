package io.pockethive.iso8583;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Responsibility: enforce the ISO XML boundary's prohibition of entities and external inclusions.
 * Must not: interpret J8583 guides or decide field types and inheritance.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
final class SecureIsoXml extends DefaultHandler {
  static Document parse(String xml) {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setNamespaceAware(true);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new SecureIsoXml());
      var document = builder.parse(new InputSource(new StringReader(xml)));
      rejectInclusions(document.getDocumentElement());
      return document;
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid ISO XML; external declarations and inclusions are forbidden");
    }
  }

  private static void rejectInclusions(Element element) {
    if ("include".equals(element.getLocalName())
        || "http://www.w3.org/2001/XInclude".equals(element.getNamespaceURI())) {
      throw new IllegalArgumentException("External ISO XML inclusions are forbidden");
    }
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element child) {
        rejectInclusions(child);
      }
    }
  }

  @Override
  public void error(SAXParseException ex) throws SAXException {
    throw ex;
  }

  @Override
  public void fatalError(SAXParseException ex) throws SAXException {
    throw ex;
  }
}
