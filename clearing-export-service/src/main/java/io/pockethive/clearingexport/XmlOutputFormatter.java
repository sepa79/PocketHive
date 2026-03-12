package io.pockethive.clearingexport;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

@Component
class XmlOutputFormatter {

  String format(
      ClearingStructuredSchema schema,
      Map<String, String> headerValues,
      List<Map<String, String>> records,
      Map<String, String> footerValues
  ) {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(headerValues, "headerValues");
    Objects.requireNonNull(records, "records");
    Objects.requireNonNull(footerValues, "footerValues");

    ClearingStructuredSchema.XmlOutputConfig xml = schema.xml();
    StringWriter writer = new StringWriter();
    try {
      XMLStreamWriter xw = XMLOutputFactory.newFactory().createXMLStreamWriter(writer);
      if (xml.declaration()) {
        xw.writeStartDocument(xml.encoding(), "1.0");
      }
      writeStartElement(xw, xml.namespaceUri(), xml.namespacePrefix(), xml.rootElement());
      if (!xml.namespaceUri().isBlank()) {
        xw.writeNamespace(xml.namespacePrefix().isBlank() ? "" : xml.namespacePrefix(), xml.namespaceUri());
      }

      writeMapAsElement(xw, xml.namespaceUri(), xml.namespacePrefix(), xml.headerElement(), headerValues);

      boolean hasRecordsWrapper = xml.recordsElement() != null && !xml.recordsElement().isBlank();
      if (hasRecordsWrapper) {
        writeStartElement(xw, xml.namespaceUri(), xml.namespacePrefix(), xml.recordsElement());
      }
      for (Map<String, String> record : records) {
        String recNs = xml.recordNamespaceUri().isBlank() ? xml.namespaceUri() : xml.recordNamespaceUri();
        String recPrefix = xml.recordNamespaceUri().isBlank() ? xml.namespacePrefix() : xml.recordNamespacePrefix();
        if (xml.recordElement().isBlank()) {
          writeInlineMapContent(xw, recNs, recPrefix, record);
        } else {
          writeMapAsElement(xw, recNs, recPrefix, xml.recordElement(), record);
        }
      }
      if (hasRecordsWrapper) {
        xw.writeEndElement();
      }

      writeMapAsElement(xw, xml.namespaceUri(), xml.namespacePrefix(), xml.footerElement(), footerValues);
      xw.writeEndElement();
      xw.writeEndDocument();
      xw.flush();
      xw.close();
      return writer.toString();
    } catch (XMLStreamException ex) {
      throw new IllegalStateException("Failed to format XML clearing output", ex);
    }
  }

  private void writeMapAsElement(
      XMLStreamWriter xw,
      String namespaceUri,
      String namespacePrefix,
      String elementName,
      Map<String, String> values
  ) throws XMLStreamException {
    Map<String, Object> tree = toTree(values);
    writeStartElement(xw, namespaceUri, namespacePrefix, elementName);
    writeAttributes(xw, tree);
    writeTreeContent(xw, namespaceUri, namespacePrefix, tree);
    xw.writeEndElement();
  }

  private void writeInlineMapContent(
      XMLStreamWriter xw,
      String namespaceUri,
      String namespacePrefix,
      Map<String, String> values
  ) throws XMLStreamException {
    Map<String, Object> tree = toTree(values);
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      String key = entry.getKey();
      if (key.startsWith("@") || "#text".equals(key)) {
        throw new IllegalStateException(
            "Inline record content does not support top-level attributes or #text entries: " + key);
      }
      writeTreeEntry(xw, namespaceUri, namespacePrefix, key, entry.getValue());
    }
  }

  @SuppressWarnings("unchecked")
  private void writeTreeEntry(
      XMLStreamWriter xw,
      String namespaceUri,
      String namespacePrefix,
      String key,
      Object value
  ) throws XMLStreamException {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> nested = (Map<String, Object>) map;
      writeStartElement(xw, namespaceUri, namespacePrefix, key);
      writeAttributes(xw, nested);
      // #text = text content alongside attributes
      Object textContent = nested.get("#text");
      if (textContent != null) {
        xw.writeCharacters(textContent.toString());
      } else {
        writeTreeContent(xw, namespaceUri, namespacePrefix, nested);
      }
      xw.writeEndElement();
    } else {
      writeStartElement(xw, namespaceUri, namespacePrefix, key);
      xw.writeCharacters(value == null ? "" : value.toString());
      xw.writeEndElement();
    }
  }

  /**
   * Converts flat dot-notation map to nested map tree.
   * Keys starting with @ are attribute markers and are kept as-is at their level.
   * Example: "TtlAmt.@Ccy" -> tree["TtlAmt"]["@Ccy"]
   *          "TtlAmt.#text" -> tree["TtlAmt"]["#text"] (text content alongside attributes)
   */
  private Map<String, Object> toTree(Map<String, String> flat) {
    Map<String, Object> root = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : flat.entrySet()) {
      String[] segments = entry.getKey().split("\\.");
      Map<String, Object> current = root;
      for (int i = 0; i < segments.length - 1; i++) {
        String segment = segments[i];
        Object existing = current.get(segment);
        if (!(existing instanceof Map<?, ?>)) {
          Map<String, Object> child = new LinkedHashMap<>();
          current.put(segment, child);
          current = child;
        } else {
          @SuppressWarnings("unchecked")
          Map<String, Object> nestedMap = (Map<String, Object>) existing;
          current = nestedMap;
        }
      }
      String lastSegment = segments[segments.length - 1];
      // If last segment is #text, store as text content marker in parent map
      current.put(lastSegment, entry.getValue());
    }
    return root;
  }

  private void writeAttributes(XMLStreamWriter xw, Map<String, Object> tree) throws XMLStreamException {
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      if (entry.getKey().startsWith("@") && entry.getValue() instanceof String s) {
        xw.writeAttribute(entry.getKey().substring(1), s);
      }
    }
  }

  private void writeTreeContent(
      XMLStreamWriter xw,
      String namespaceUri,
      String namespacePrefix,
      Map<String, Object> tree
  ) throws XMLStreamException {
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      String key = entry.getKey();
      if (!key.startsWith("@") && !"#text".equals(key)) {
        writeTreeEntry(xw, namespaceUri, namespacePrefix, key, entry.getValue());
      }
    }
  }

  private void writeStartElement(
      XMLStreamWriter xw,
      String namespaceUri,
      String namespacePrefix,
      String localName
  ) throws XMLStreamException {
    if (namespaceUri != null && !namespaceUri.isBlank()) {
      String prefix = namespacePrefix == null ? "" : namespacePrefix;
      if (!prefix.isBlank()) {
        xw.writeStartElement(prefix, localName, namespaceUri);
      } else {
        xw.writeStartElement("", localName, namespaceUri);
      }
    } else {
      xw.writeStartElement(localName);
    }
  }
}
