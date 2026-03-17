package io.pockethive.requestbuilder;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

final class J8583FieldListXmlCodec {

  private final Iso8583SchemaPackRegistry schemaPackRegistry;

  J8583FieldListXmlCodec(Iso8583SchemaPackRegistry schemaPackRegistry) {
    this.schemaPackRegistry = schemaPackRegistry;
  }

  byte[] encodePayload(String xmlPayload, Iso8583RequestEnvelope.IsoSchemaRef schemaRef) {
    if (schemaRef == null) {
      throw new IllegalArgumentException("schemaRef must not be null for FIELD_LIST_XML");
    }

    Iso8583SchemaPackRegistry.ResolvedSchema resolvedSchema = schemaPackRegistry.resolve(schemaRef);
    MessageFactory<IsoMessage> messageFactory = resolvedSchema.messageFactory();

    ParsedIsoXmlMessage parsed = parsePayload(xmlPayload);
    IsoMessage isoMessage = messageFactory.newMessage(parsed.mti());
    Map<Integer, Iso8583SchemaPackRegistry.FieldDefinition> parseGuide =
        resolvedSchema.schemaDefinition().fieldsForMti(parsed.mti());

    for (IsoFieldValue field : parsed.fields()) {
      Iso8583SchemaPackRegistry.FieldDefinition fieldDefinition = parseGuide.get(field.number());
      if (fieldDefinition == null) {
        throw new IllegalArgumentException("Field " + field.number() + " is not defined for MTI " + parsed.mtiHex());
      }
      setFieldValue(isoMessage, field.number(), fieldDefinition, field.value());
    }

    return isoMessage.writeData();
  }

  private void setFieldValue(IsoMessage message,
                             int fieldNumber,
                             Iso8583SchemaPackRegistry.FieldDefinition fieldDefinition,
                             String rawValue) {
    IsoType fieldType = fieldDefinition.type();
    int length = fieldDefinition.length();
    Object value = switch (fieldType) {
      case BINARY, LLBIN, LLLBIN, LLLLBIN, LLBCDBIN, LLLBCDBIN, LLLLBCDBIN -> decodeHex(rawValue);
      case AMOUNT -> parseAmount(rawValue);
      default -> normalizedText(rawValue, fieldNumber);
    };
    message.setValue(fieldNumber, value, fieldType, length);
  }

  private ParsedIsoXmlMessage parsePayload(String xmlPayload) {
    if (xmlPayload == null || xmlPayload.isBlank()) {
      throw new IllegalArgumentException("FIELD_LIST_XML payload must not be blank");
    }
    Document document = parse(xmlPayload);
    Element root = document.getDocumentElement();
    if (root == null || !"iso8583".equals(root.getTagName())) {
      throw new IllegalArgumentException("FIELD_LIST_XML root element must be <iso8583>");
    }

    String mtiText = root.getAttribute("mti");
    if (mtiText == null || mtiText.isBlank()) {
      throw new IllegalArgumentException("FIELD_LIST_XML requires iso8583@mti");
    }
    int mti = parseMti(mtiText);
    List<IsoFieldValue> fields = parseFields(root);
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("FIELD_LIST_XML contains no <field> elements");
    }
    return new ParsedIsoXmlMessage(mti, fields);
  }

  private Document parse(String xmlPayload) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      dbf.setNamespaceAware(false);
      DocumentBuilder builder = dbf.newDocumentBuilder();
      return builder.parse(new InputSource(new StringReader(xmlPayload)));
    } catch (Exception ex) {
      throw new IllegalArgumentException("Failed to parse FIELD_LIST_XML payload", ex);
    }
  }

  private int parseMti(String mtiText) {
    String normalized = mtiText.trim().toUpperCase();
    if (!normalized.matches("[0-9A-F]{4}")) {
      throw new IllegalArgumentException("Invalid MTI in FIELD_LIST_XML: " + mtiText);
    }
    return Integer.parseInt(normalized, 16);
  }

  private List<IsoFieldValue> parseFields(Element root) {
    List<IsoFieldValue> fields = new ArrayList<>();
    NodeList nodes = root.getElementsByTagName("field");
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (!(node instanceof Element field)) {
        continue;
      }
      String numText = field.getAttribute("num");
      if (numText == null || numText.isBlank()) {
        throw new IllegalArgumentException("FIELD_LIST_XML field is missing required attribute 'num'");
      }
      int fieldNumber;
      try {
        fieldNumber = Integer.parseInt(numText.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("Invalid field number in FIELD_LIST_XML: " + numText, ex);
      }
      if (fieldNumber < 2 || fieldNumber > 128) {
        throw new IllegalArgumentException("FIELD_LIST_XML field number out of range: " + fieldNumber);
      }

      String value = field.getAttribute("value");
      if (value == null || value.isEmpty()) {
        value = field.getTextContent();
      }
      if (value == null || value.trim().isEmpty()) {
        throw new IllegalArgumentException("FIELD_LIST_XML field " + fieldNumber + " has empty value");
      }
      fields.add(new IsoFieldValue(fieldNumber, value.trim()));
    }
    return fields;
  }

  private byte[] decodeHex(String value) {
    String normalized = normalizedText(value, -1).replaceAll("\\s+", "");
    if ((normalized.length() & 1) != 0) {
      throw new IllegalArgumentException("Invalid hex length for binary ISO field");
    }
    try {
      return HexFormat.of().parseHex(normalized);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid hex value for binary ISO field", ex);
    }
  }

  private BigDecimal parseAmount(String value) {
    String normalized = normalizedText(value, -1);
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Invalid amount value: " + value, ex);
    }
  }

  private String normalizedText(String value, int fieldNumber) {
    if (value == null) {
      if (fieldNumber < 0) {
        throw new IllegalArgumentException("ISO field value must not be null");
      }
      throw new IllegalArgumentException("ISO field " + fieldNumber + " value must not be null");
    }
    String normalized = value.trim();
    if (normalized.isEmpty()) {
      if (fieldNumber < 0) {
        throw new IllegalArgumentException("ISO field value must not be blank");
      }
      throw new IllegalArgumentException("ISO field " + fieldNumber + " value must not be blank");
    }
    return normalized;
  }

  private record IsoFieldValue(int number, String value) {
  }

  private record ParsedIsoXmlMessage(int mti, List<IsoFieldValue> fields) {
    private String mtiHex() {
      return String.format("%04X", mti);
    }
  }
}
