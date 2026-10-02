package io.pockethive.iso8583;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.parse.FieldParseInfo;
import io.pockethive.work.api.IsoSchemaRef;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Responsibility: encode authored fields and decode ISO wire bytes against the selected canonical pack.
 * Must not: perform network effects, own sessions or calculate business transaction outcomes.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
public final class Iso8583Codec {
  private static final String PAYLOAD_ROOT = "iso8583";
  private static final String FIELD_ELEMENT = "field";
  private final Iso8583SchemaPackRegistry registry = new Iso8583SchemaPackRegistry();

  public void validate(IsoSchemaRef reference, Set<Integer> requiredMtis) {
    SchemaMessageFactory factory = registry.resolve(reference);
    Objects.requireNonNull(requiredMtis, "requiredMtis").forEach(mti -> factory.guide(validateMti(mti)));
  }

  public void validateFields(IsoSchemaRef reference, int mti, Set<Integer> fields) {
    Map<Integer, FieldParseInfo> guide = registry.resolve(reference).guide(validateMti(mti));
    Objects.requireNonNull(fields, "fields").forEach(number -> requireField(guide, number, mti));
  }

  public byte[] encodePayload(String xmlPayload, IsoSchemaRef reference) {
    if (xmlPayload == null || xmlPayload.isBlank()) {
      throw new IllegalArgumentException("FIELD_LIST_XML payload must not be blank");
    }
    Element root = SecureIsoXml.parse(xmlPayload).getDocumentElement();
    if (!PAYLOAD_ROOT.equals(root.getTagName())) {
      throw new IllegalArgumentException("FIELD_LIST_XML root must be iso8583");
    }
    String mtiText = root.getAttribute("mti");
    if (!mtiText.matches("[0-9A-Fa-f]{4}")) {
      throw new IllegalArgumentException("FIELD_LIST_XML requires a four-digit hexadecimal MTI");
    }
    int mti = Integer.parseInt(mtiText, 16);
    Map<Integer, FieldParseInfo> guide = registry.resolve(reference).guide(mti);
    Map<Integer, String> fields = new LinkedHashMap<>();
    for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (!(node instanceof Element field)) {
        if (node.getNodeType() == Node.TEXT_NODE && !node.getTextContent().isBlank()) {
          throw new IllegalArgumentException("Unexpected FIELD_LIST_XML text");
        }
        continue;
      }
      if (!FIELD_ELEMENT.equals(field.getTagName())) {
        throw new IllegalArgumentException("Unexpected FIELD_LIST_XML element");
      }
      int number;
      try {
        number = Integer.parseInt(field.getAttribute("num"));
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException("FIELD_LIST_XML requires an integer field number", ex);
      }
      for (Node child = field.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof Element) {
          throw new IllegalArgumentException("Nested FIELD_LIST_XML fields are forbidden");
        }
      }
      if (field.hasAttribute("value") && !field.getTextContent().isBlank()) {
        throw new IllegalArgumentException("FIELD_LIST_XML field value has two sources");
      }
      String value = field.hasAttribute("value") ? field.getAttribute("value") : field.getTextContent();
      FieldParseInfo parser = requireField(guide, number, mti);
      if (parser.getType() == IsoType.AMOUNT) {
        try {
          // FIELD_LIST_XML's existing amount input is a decimal; public encode consumes wire digits.
          value = new BigDecimal(value.trim()).movePointRight(2).toBigIntegerExact().toString();
        } catch (ArithmeticException | NumberFormatException ex) {
          throw new IllegalArgumentException("Invalid decimal ISO amount field " + number);
        }
      } else {
        value = value.trim();
      }
      if (fields.putIfAbsent(number, value) != null) {
        throw new IllegalArgumentException("Duplicate FIELD_LIST_XML field " + number);
      }
    }
    if (fields.isEmpty()) {
      throw new IllegalArgumentException("FIELD_LIST_XML contains no fields");
    }
    return encode(mti, fields, reference);
  }

  public byte[] encode(int mti, Map<Integer, String> fields, IsoSchemaRef reference) {
    SchemaMessageFactory factory = registry.resolve(reference);
    Map<Integer, FieldParseInfo> guide = factory.guide(validateMti(mti));
    IsoMessage message = factory.newMessage(mti);
    Objects.requireNonNull(fields, "fields").forEach((number, value) -> {
      FieldParseInfo parser = requireField(guide, number, mti);
      validateValue(number, value, parser);
      Object typed = isBinary(parser.getType()) ? HexFormat.of().parseHex(value)
          : parser.getType() == IsoType.AMOUNT ? new BigDecimal(value).movePointLeft(2) : value;
      message.setValue(number, typed, parser.getType(), parser.getLength());
    });
    return message.writeData();
  }

  public Iso8583Message decode(byte[] bytes, IsoSchemaRef reference) {
    if (bytes == null || bytes.length < 12) {
      throw new IllegalArgumentException("Truncated ISO8583 message");
    }
    SchemaMessageFactory factory = registry.resolve(reference);
    String mtiText = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
    if (!mtiText.matches("[0-9A-Fa-f]{4}")) {
      throw new IllegalArgumentException("Invalid ISO8583 wire MTI");
    }
    int mti = Integer.parseInt(mtiText, 16);
    Map<Integer, FieldParseInfo> guide = factory.guide(mti);
    try {
      IsoMessage message = factory.parseMessage(bytes, 0);
      Map<Integer, String> fields = new LinkedHashMap<>();
      for (int number = 2; number <= 128; number++) {
        if (message.hasField(number)) {
          FieldParseInfo parser = requireField(guide, number, mti);
          String value = message.getField(number).toString();
          validateValue(number, value, parser);
          fields.put(number, value);
        }
      }
      // J8583 tolerates trailing bytes. Canonical reserialization verifies complete consumption.
      if (!Arrays.equals(bytes, message.writeData())) {
        throw new IllegalArgumentException("Non-canonical, truncated or trailing ISO8583 wire bytes");
      }
      return new Iso8583Message(mti, fields);
    } catch (Exception ex) {
      // Library parse failures may contain wire values. Keep only boundary identity in propagated errors.
      throw new IllegalArgumentException("Invalid ISO8583 message for MTI %04X".formatted(mti));
    }
  }

  private int validateMti(int mti) {
    if (mti < 0 || mti > 0xffff) {
      throw new IllegalArgumentException("ISO8583 MTI is outside the four-digit range");
    }
    return mti;
  }

  private FieldParseInfo requireField(Map<Integer, FieldParseInfo> guide, Integer number, int mti) {
    if (number == null || number < 2 || number > 128 || !guide.containsKey(number)) {
      throw new IllegalArgumentException("Undefined ISO field " + number + " for MTI %04X".formatted(mti));
    }
    return guide.get(number);
  }

  private void validateValue(int number, String value, FieldParseInfo parser) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException("Empty ISO field " + number);
    }
    IsoType type = parser.getType();
    if (isBinary(type)) {
      if ((value.length() & 1) != 0 || !value.matches("[0-9A-Fa-f]+")) {
        throw new IllegalArgumentException("Invalid hexadecimal ISO field " + number);
      }
      if (type == IsoType.BINARY && value.length() != parser.getLength() * 2) {
        throw new IllegalArgumentException("Invalid fixed binary ISO field length " + number);
      }
      checkVariableLength(number, type, value.length() / 2);
      return;
    }
    if (!StandardCharsets.US_ASCII.newEncoder().canEncode(value)) {
      throw new IllegalArgumentException("Non-ASCII ISO field " + number);
    }
    if (type == IsoType.NUMERIC || type == IsoType.AMOUNT || type.isDateTimeType()) {
      if (!value.matches("[0-9]+")) {
        throw new IllegalArgumentException("Non-numeric ISO field " + number);
      }
    }
    if (type.isDateTimeType() && value.length() != parser.getLength()) {
      throw new IllegalArgumentException("Invalid date/time ISO field length " + number);
    }
    int fixedLength = type == IsoType.AMOUNT ? 12 : parser.getLength();
    if ((type.needsLength() || type == IsoType.AMOUNT) && fixedLength > 0 && value.length() > fixedLength) {
      throw new IllegalArgumentException("Oversized ISO field " + number);
    }
    checkVariableLength(number, type, value.length());
  }

  private void checkVariableLength(int number, IsoType type, int length) {
    int maximum = switch (type) {
      case LLVAR, LLBIN, LLBCDBIN -> 99;
      case LLLVAR, LLLBIN, LLLBCDBIN -> 999;
      case LLLLVAR, LLLLBIN, LLLLBCDBIN -> 9999;
      default -> Integer.MAX_VALUE;
    };
    if (length > maximum) {
      throw new IllegalArgumentException("Oversized variable ISO field " + number);
    }
  }

  private boolean isBinary(IsoType type) {
    return switch (type) {
      case BINARY, LLBIN, LLLBIN, LLLLBIN, LLBCDBIN, LLLBCDBIN, LLLLBCDBIN -> true;
      default -> false;
    };
  }
}
