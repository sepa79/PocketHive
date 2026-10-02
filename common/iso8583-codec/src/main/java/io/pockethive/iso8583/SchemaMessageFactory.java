package io.pockethive.iso8583;

import com.solab.iso8583.IsoMessage;
import com.solab.iso8583.MessageFactory;
import com.solab.iso8583.parse.FieldParseInfo;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Responsibility: expose J8583's canonical guides and retain wire text for declared dates and times.
 * Must not: parse a second schema model or inject template fields into authored messages.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
final class SchemaMessageFactory extends MessageFactory<IsoMessage> {
  SchemaMessageFactory() {
    setCharacterEncoding(StandardCharsets.US_ASCII.name());
    setUseBinaryBitmap(true);
    setUseBinaryMessages(false);
    setBinaryFields(false);
    setForceStringEncoding(true);
    setIgnoreLastMissingField(false);
    setAssignDate(false);
  }

  Map<Integer, FieldParseInfo> guide(int mti) {
    Map<Integer, FieldParseInfo> fields = parseMap.get(mti);
    if (fields == null || fields.isEmpty()) {
      throw new IllegalArgumentException("No ISO schema parse guide for MTI %04X".formatted(mti));
    }
    return fields;
  }

  void prepareGuides() {
    if (parseMap.isEmpty()) {
      throw new IllegalArgumentException("ISO schema contains no parse guides");
    }
    for (var entry : parseMap.entrySet()) {
      int mti = entry.getKey();
      if (getIsoHeader(mti) != null || getBinaryIsoHeader(mti) != null) {
        throw new IllegalArgumentException("ISO schema headers are not supported by this wire profile");
      }
      removeMessageTemplate(mti);
      entry.getValue().replaceAll((number, parser) -> {
        if (number < 2 || number > 128) {
          throw new IllegalArgumentException("ISO schema field number out of range: " + number);
        }
        return parser.getType().isDateTimeType()
            ? new WireDateFieldParseInfo(parser.getType(), parser.getLength()) : parser;
      });
    }
    freeze();
  }
}
