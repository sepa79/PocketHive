package io.pockethive.iso8583;

import com.solab.iso8583.CustomField;
import com.solab.iso8583.IsoType;
import com.solab.iso8583.IsoValue;
import com.solab.iso8583.parse.FieldParseInfo;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;

/**
 * Responsibility: preserve declared ISO date/time fields as wire digits without calendar inference.
 * Must not: interpret schema inheritance or change a field's declared type and length.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
final class WireDateFieldParseInfo extends FieldParseInfo {
  WireDateFieldParseInfo(IsoType type, int length) {
    super(type, length);
  }

  @Override
  public <T> IsoValue<?> parse(int field, byte[] bytes, int offset, CustomField<T> decoder)
      throws ParseException {
    if (offset < 0 || offset + length > bytes.length) {
      throw new ParseException("Truncated date/time ISO field " + field, offset);
    }
    for (int index = offset; index < offset + length; index++) {
      if (bytes[index] < '0' || bytes[index] > '9') {
        throw new ParseException("Non-numeric date/time ISO field " + field, index);
      }
    }
    return new IsoValue<>(type, new String(bytes, offset, length, StandardCharsets.US_ASCII), length);
  }

  @Override
  public <T> IsoValue<?> parseBinary(int field, byte[] bytes, int offset, CustomField<T> decoder)
      throws ParseException {
    throw new ParseException("Binary ISO date/time fields are not supported by this wire profile", offset);
  }
}
