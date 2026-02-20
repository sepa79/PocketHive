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
        String prefix = xml.namespacePrefix().isBlank() ? "xmlns" : "xmlns:" + xml.namespacePrefix();
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
        writeMapAsElement(xw, recNs, recPrefix, xml.recordElement(), record);
        if (!xml.recordNamespaceUri().isBlank()) {
          // namespace already written via writeStartElement; declare it on the element
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
    // write attributes first (@key entries at top level)
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      if (entry.getKey().startsWith("@") && entry.getValue() instanceof String s) {
        xw.writeAttribute(entry.getKey().substring(1), s);
      }
    }
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      if (!entry.getKey().startsWith("@")) {
        writeTreeEntry(xw, namespaceUri, namespacePrefix, entry.getKey(), entry.getValue());
      }
    }
    xw.writeEndElement();
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
      // write attributes on this element
      for (Map.Entry<String, Object> entry : nested.entrySet()) {
        if (entry.getKey().startsWith("@") && entry.getValue() instanceof String s) {
          xw.writeAttribute(entry.getKey().substring(1), s);
        }
      }
      // #text = text content alongside attributes
      Object textContent = nested.get("#text");
      if (textContent != null) {
        xw.writeCharacters(textContent.toString());
      } else {
        for (Map.Entry<String, Object> entry : nested.entrySet()) {
          if (!entry.getKey().startsWith("@")) {
            writeTreeEntry(xw, namespaceUri, namespacePrefix, entry.getKey(), entry.getValue());
          }
        }
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
