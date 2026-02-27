package io.pockethive.requestbuilder;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.ConfigParser;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

final class Iso8583SchemaPackRegistry {
  private final Map<String, ThreadLocal<MessageFactory<IsoMessage>>> factoryCache = new ConcurrentHashMap<>();
  private final Map<String, IsoSchemaDefinition> schemaCache = new ConcurrentHashMap<>();

  ResolvedSchema resolve(Iso8583RequestEnvelope.IsoSchemaRef schemaRef) {
    Objects.requireNonNull(schemaRef, "schemaRef");
    if (!"J8583_XML".equals(schemaRef.schemaAdapter())) {
      throw new IllegalArgumentException("Unsupported ISO8583 schemaAdapter: " + schemaRef.schemaAdapter());
    }

    Path schemaPath = resolveSchemaPath(schemaRef);
    String key = cacheKey(schemaRef);

    ThreadLocal<MessageFactory<IsoMessage>> holder = factoryCache.computeIfAbsent(
        key,
        ignored -> ThreadLocal.withInitial(() -> loadJ8583Xml(schemaPath)));
    IsoSchemaDefinition schemaDefinition = schemaCache.computeIfAbsent(
        key,
        ignored -> loadSchemaDefinition(schemaPath));

    return new ResolvedSchema(holder.get(), schemaDefinition);
  }

  private Path resolveSchemaPath(Iso8583RequestEnvelope.IsoSchemaRef schemaRef) {
    Path root = Path.of(schemaRef.schemaRegistryRoot()).toAbsolutePath().normalize();
    Path base = root.resolve(schemaRef.schemaId()).resolve(schemaRef.schemaVersion()).normalize();
    Path schemaPath = base.resolve(schemaRef.schemaFile()).normalize();
    if (!schemaPath.startsWith(base)) {
      throw new IllegalArgumentException("Invalid schemaFile path: " + schemaRef.schemaFile());
    }
    if (!Files.isRegularFile(schemaPath)) {
      throw new IllegalStateException("Schema not found: " + schemaPath);
    }
    return schemaPath;
  }

  private String cacheKey(Iso8583RequestEnvelope.IsoSchemaRef schemaRef) {
    return schemaRef.schemaRegistryRoot()
        + ":"
        + schemaRef.schemaId()
        + ":"
        + schemaRef.schemaVersion()
        + ":"
        + schemaRef.schemaFile()
        + ":"
        + schemaRef.schemaAdapter();
  }

  private MessageFactory<IsoMessage> loadJ8583Xml(Path schemaPath) {
    try (Reader reader = Files.newBufferedReader(schemaPath, StandardCharsets.UTF_8)) {
      MessageFactory<IsoMessage> factory = ConfigParser.createFromReader(reader);
      factory.setUseBinaryBitmap(true);
      return factory;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load ISO8583 schema: " + schemaPath, ex);
    }
  }

  private IsoSchemaDefinition loadSchemaDefinition(Path schemaPath) {
    Document document = parseXml(schemaPath);
    NodeList parseNodes = document.getElementsByTagName("parse");
    Map<Integer, Map<Integer, FieldDefinition>> fieldsByMti = new LinkedHashMap<>();

    for (int i = 0; i < parseNodes.getLength(); i++) {
      Node node = parseNodes.item(i);
      if (!(node instanceof Element parseElement)) {
        continue;
      }
      int mti = parseMti(parseElement.getAttribute("type"));
      Map<Integer, FieldDefinition> fields = parseFields(parseElement);
      if (!fields.isEmpty()) {
        fieldsByMti.put(mti, Map.copyOf(fields));
      }
    }

    if (fieldsByMti.isEmpty()) {
      throw new IllegalStateException("No <parse> field definitions found in schema: " + schemaPath);
    }
    return new IsoSchemaDefinition(Map.copyOf(fieldsByMti));
  }

  private Document parseXml(Path schemaPath) {
    try {
      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      dbf.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      dbf.setNamespaceAware(false);
      return dbf.newDocumentBuilder().parse(schemaPath.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse ISO8583 schema XML: " + schemaPath, ex);
    }
  }

  private int parseMti(String mtiText) {
    if (mtiText == null || mtiText.isBlank()) {
      throw new IllegalStateException("Schema <parse> element is missing required @type (MTI)");
    }
    String normalized = mtiText.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[0-9A-F]{4}")) {
      throw new IllegalStateException("Invalid schema MTI: " + mtiText);
    }
    return Integer.parseInt(normalized, 16);
  }

  private Map<Integer, FieldDefinition> parseFields(Element parseElement) {
    Map<Integer, FieldDefinition> fields = new LinkedHashMap<>();
    NodeList children = parseElement.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (!(node instanceof Element fieldElement) || !"field".equals(fieldElement.getTagName())) {
        continue;
      }
      int fieldNumber = parseFieldNumber(fieldElement.getAttribute("num"));
      IsoType isoType = parseIsoType(fieldElement.getAttribute("type"));
      int length = parseLength(fieldElement.getAttribute("length"), isoType);
      fields.put(fieldNumber, new FieldDefinition(isoType, length));
    }
    return fields;
  }

  private int parseFieldNumber(String fieldNumberText) {
    if (fieldNumberText == null || fieldNumberText.isBlank()) {
      throw new IllegalStateException("Schema field is missing required @num");
    }
    try {
      int field = Integer.parseInt(fieldNumberText.trim());
      if (field < 2 || field > 128) {
        throw new IllegalStateException("Schema field number out of range: " + field);
      }
      return field;
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid schema field number: " + fieldNumberText, ex);
    }
  }

  private IsoType parseIsoType(String isoTypeText) {
    if (isoTypeText == null || isoTypeText.isBlank()) {
      throw new IllegalStateException("Schema field is missing required @type");
    }
    try {
      return IsoType.valueOf(isoTypeText.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException("Unsupported ISO field type in schema: " + isoTypeText, ex);
    }
  }

  private int parseLength(String lengthText, IsoType isoType) {
    if (lengthText == null || lengthText.isBlank()) {
      return Math.max(0, isoType.getLength());
    }
    try {
      int parsed = Integer.parseInt(lengthText.trim());
      if (parsed < 0) {
        throw new IllegalStateException("Schema field length must be >= 0");
      }
      return parsed;
    } catch (NumberFormatException ex) {
      throw new IllegalStateException("Invalid schema field length: " + lengthText, ex);
    }
  }

  record ResolvedSchema(
      MessageFactory<IsoMessage> messageFactory,
      IsoSchemaDefinition schemaDefinition
  ) {
  }

  record IsoSchemaDefinition(Map<Integer, Map<Integer, FieldDefinition>> fieldsByMti) {
    Map<Integer, FieldDefinition> fieldsForMti(int mti) {
      Map<Integer, FieldDefinition> fields = fieldsByMti.get(mti);
      if (fields == null || fields.isEmpty()) {
        throw new IllegalArgumentException("No schema parse guide found for MTI " + String.format("%04X", mti));
      }
      return fields;
    }
  }

  record FieldDefinition(IsoType type, int length) {
  }
}
