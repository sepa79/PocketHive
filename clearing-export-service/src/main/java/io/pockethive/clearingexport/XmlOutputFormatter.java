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
      writeStartElement(xw, xml, xml.rootElement());
      writeMapAsElement(xw, xml, xml.headerElement(), headerValues);

      if (xml.recordsElement() != null && !xml.recordsElement().isBlank()) {
        writeStartElement(xw, xml, xml.recordsElement());
      }
      for (Map<String, String> record : records) {
        writeMapAsElement(xw, xml, xml.recordElement(), record);
      }
      if (xml.recordsElement() != null && !xml.recordsElement().isBlank()) {
        xw.writeEndElement();
      }

      writeMapAsElement(xw, xml, xml.footerElement(), footerValues);
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
      ClearingStructuredSchema.XmlOutputConfig xml,
      String elementName,
      Map<String, String> values
  ) throws XMLStreamException {
    writeStartElement(xw, xml, elementName);
    Map<String, Object> tree = toTree(values);
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      writeTreeEntry(xw, xml, entry.getKey(), entry.getValue());
    }
    xw.writeEndElement();
  }

  @SuppressWarnings("unchecked")
  private void writeTreeEntry(
      XMLStreamWriter xw,
      ClearingStructuredSchema.XmlOutputConfig xml,
      String key,
      Object value
  ) throws XMLStreamException {
    writeStartElement(xw, xml, key);
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<String, Object> nested : ((Map<String, Object>) map).entrySet()) {
        writeTreeEntry(xw, xml, nested.getKey(), nested.getValue());
      }
    } else {
      xw.writeCharacters(value == null ? "" : value.toString());
    }
    xw.writeEndElement();
  }

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
          Map<String, Object> nested = (Map<String, Object>) existing;
          current = nested;
        }
      }
      current.put(segments[segments.length - 1], entry.getValue());
    }
    return root;
  }

  private void writeStartElement(
      XMLStreamWriter xw,
      ClearingStructuredSchema.XmlOutputConfig xml,
      String localName
  ) throws XMLStreamException {
    if (xml.namespaceUri() != null && !xml.namespaceUri().isBlank()) {
      String prefix = xml.namespacePrefix() == null ? "" : xml.namespacePrefix();
      if (!prefix.isBlank()) {
        xw.writeStartElement(prefix, localName, xml.namespaceUri());
      } else {
        xw.writeStartElement("", localName, xml.namespaceUri());
      }
    } else {
      xw.writeStartElement(localName);
    }
  }
}

