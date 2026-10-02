package io.pockethive.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.work.api.IsoSchemaRef;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Iso8583CodecTest {
  private static final String SCHEMA = """
      <j8583-config>
        <template type="0100"><field num="3" type="NUMERIC" length="6">123456</field></template>
        <parse type="0100">
          <field num="3" type="NUMERIC" length="6"/>
          <field num="4" type="AMOUNT"/>
          <field num="7" type="DATE10"/>
          <field num="11" type="NUMERIC" length="6"/>
          <field num="13" type="DATE4"/>
          <field num="39" type="ALPHA" length="2"/>
          <field num="48" type="LLLBIN"/>
          <field num="70" type="NUMERIC" length="3"/>
        </parse>
        <parse type="0110" extends="0100"/>
        <parse type="0800" extends="0100"/>
        <parse type="0810" extends="0800"/>
      </j8583-config>
      """;

  @TempDir Path root;
  private Path schemaFile;
  private IsoSchemaRef reference;
  private final Iso8583Codec codec = new Iso8583Codec();

  @BeforeEach
  void createSchema() throws Exception {
    Path directory = root.resolve("synthetic").resolve("1.0");
    Files.createDirectories(directory);
    schemaFile = directory.resolve("guide.xml");
    Files.writeString(schemaFile, SCHEMA);
    reference = reference("synthetic", "1.0", "guide.xml");
  }

  @Test
  void validatesFourMtisAndUsesInheritedTypesWithBinarySecondaryBitmap() {
    codec.validate(reference, Set.of(0x0100, 0x0110, 0x0800, 0x0810));
    byte[] wire = codec.encode(0x0810, Map.of(11, "42", 39, "00", 70, "1"), reference);

    assertThat(new String(wire, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("0810");
    assertThat(wire[4] & 0x80).isEqualTo(0x80);
    Iso8583Message decoded = codec.decode(wire, reference);
    assertThat(decoded.mti()).isEqualTo(0x0810);
    assertThat(decoded.fields()).containsExactlyInAnyOrderEntriesOf(
        Map.of(11, "000042", 39, "00", 70, "001"));
    assertThat(decoded.field(11)).isEqualTo("000042");
    assertThat(codec.encode(decoded.mti(), decoded.fields(), reference)).containsExactly(wire);
  }

  @Test
  void validatesSelectedFieldsAgainstTheInheritedGuide() {
    codec.validateFields(reference, 0x0810, Set.of(11, 39, 70));
    assertThatThrownBy(() -> codec.validateFields(reference, 0x0810, Set.of(12)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Undefined ISO field 12");
    assertThatThrownBy(() -> codec.validateFields(reference, 0x0200, Set.of(11)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0200");
  }

  @Test
  void keepsReceivedDateDigitsIncludingLeapDayWithoutInferringCurrentYear() {
    byte[] wire = codec.encode(0x0100, Map.of(7, "0229235959", 11, "8", 13, "0229"), reference);
    Iso8583Message message = codec.decode(wire, reference);
    assertThat(message.field(7)).isEqualTo("0229235959");
    assertThat(message.field(13)).isEqualTo("0229");
    assertThat(codec.encode(message.mti(), message.fields(), reference)).containsExactly(wire);
  }

  @Test
  void fieldListXmlUsesTheSameGuideAndDoesNotInjectTemplateDefaults() {
    byte[] xmlWire = codec.encodePayload("""
        <iso8583 mti="0100"><field num="11" value="42"/><field num="4">1.23</field></iso8583>
        """, reference);
    byte[] mapWire = codec.encode(0x0100, Map.of(11, "42", 4, "123"), reference);
    assertThat(xmlWire).containsExactly(mapWire);
    assertThat(codec.decode(xmlWire, reference).fields())
        .containsExactlyInAnyOrderEntriesOf(Map.of(11, "000042", 4, "000000000123"));
    assertThat(codec.decode(xmlWire, reference).field(3)).isNull();
  }

  @Test
  void preservesVariableBinaryFields() {
    byte[] wire = codec.encode(0x0110, Map.of(11, "42", 48, "00A1FF"), reference);
    assertThat(codec.decode(wire, reference).field(48)).isEqualTo("00A1FF");
    assertThat(codec.encodePayload(
        "<iso8583 mti=\"0110\"><field num=\"11\">42</field><field num=\"48\">00A1FF</field></iso8583>",
        reference)).containsExactly(wire);
  }

  @Test
  void rejectsUnknownMtiFieldsAndInvalidOrOversizedValues() {
    assertThatThrownBy(() -> codec.validate(reference, Set.of(0x0200)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0200");
    assertThatThrownBy(() -> codec.encode(0x0100, Map.of(12, "123456"), reference))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Undefined ISO field 12");
    assertThatThrownBy(() -> codec.encode(0x0100, Map.of(11, "1234567"), reference))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Oversized");
    assertThatThrownBy(() -> codec.encode(0x0100, Map.of(11, "ABC123"), reference))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Non-numeric");
    assertThatThrownBy(() -> codec.encode(0x0100, Map.of(48, "ABC"), reference))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hexadecimal");
    assertThatThrownBy(() -> codec.encode(0x0100, Map.of(13, "229"), reference))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("date/time");
  }

  @Test
  void rejectsDuplicateNestedAndAmbiguousPayloadFields() {
    assertThatThrownBy(() -> codec.encodePayload("""
        <iso8583 mti="0100"><field num="11">1</field><field num="11">2</field></iso8583>
        """, reference)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> codec.encodePayload("""
        <iso8583 mti="0100"><field num="11"><field num="3">1</field></field></iso8583>
        """, reference)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Nested");
    assertThatThrownBy(() -> codec.encodePayload("""
        <iso8583 mti="0100"><field num="11" value="1">2</field></iso8583>
        """, reference)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two sources");
  }

  @Test
  void rejectsTruncatedTrailingAndUndefinedBitmapFields() {
    byte[] wire = codec.encode(0x0110, Map.of(11, "42", 39, "00"), reference);
    for (int length = 0; length < wire.length; length++) {
      byte[] truncated = Arrays.copyOf(wire, length);
      assertThatThrownBy(() -> codec.decode(truncated, reference)).isInstanceOf(IllegalArgumentException.class);
    }
    byte[] trailing = Arrays.copyOf(wire, wire.length + 1);
    assertThatThrownBy(() -> codec.decode(trailing, reference)).isInstanceOf(IllegalArgumentException.class);
    byte[] unknownField = HexFormat.of().parseHex("303131301000000000000000313233343536");
    assertThatThrownBy(() -> codec.decode(unknownField, reference)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnsafeXmlInPayloadAndPackWithoutExternalReads() throws Exception {
    String unsafe = "<!DOCTYPE iso8583 [<!ENTITY file SYSTEM 'file:///etc/passwd'>]>"
        + "<iso8583 mti=\"0100\"><field num=\"11\">&file;</field></iso8583>";
    assertThatThrownBy(() -> codec.encodePayload(unsafe, reference)).isInstanceOf(IllegalArgumentException.class);
    Files.writeString(schemaFile, "<!DOCTYPE j8583-config SYSTEM 'https://invalid.example/schema.dtd'>" + SCHEMA);
    assertThatThrownBy(() -> codec.validate(reference, Set.of(0x0100)))
        .isInstanceOf(IllegalArgumentException.class);
    Files.writeString(schemaFile, "<j8583-config xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
        + "<xi:include href=\"https://invalid.example/guide.xml\"/></j8583-config>");
    assertThatThrownBy(() -> codec.validate(reference, Set.of(0x0100)))
        .isInstanceOf(IllegalArgumentException.class);
    Files.writeString(schemaFile, "<j8583-config><parse");
    assertThatThrownBy(() -> codec.validate(reference, Set.of(0x0100)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usesTheValidatedSnapshotInNewThreadsAfterPackModificationAndDeletion() throws Exception {
    codec.validate(reference, Set.of(0x0100, 0x0110, 0x0800, 0x0810));
    byte[] wire = codec.encode(0x0810, Map.of(11, "42", 39, "00", 70, "1"), reference);

    Files.writeString(schemaFile, "<invalid-new-pack/>");
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertThat(executor.submit(() -> codec.decode(wire, reference)).get().fields())
          .containsExactlyInAnyOrderEntriesOf(Map.of(11, "000042", 39, "00", 70, "001"));
    }

    Files.delete(schemaFile);
    try (var executor = Executors.newSingleThreadExecutor()) {
      assertThat(executor.submit(() -> codec.encode(0x0810, Map.of(11, "42", 39, "00", 70, "1"), reference))
          .get()).containsExactly(wire);
    }
    assertThat(codec.decode(wire, reference).field(11)).isEqualTo("000042");
    assertThatThrownBy(() -> new Iso8583Codec().validate(reference, Set.of(0x0100)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("not found");
  }

  @Test
  void rejectsMissingTraversalAndAbsolutePackPaths() {
    assertThatThrownBy(() -> codec.validate(reference("missing", "1.0", "guide.xml"), Set.of(0x0100)))
        .isInstanceOf(IllegalStateException.class);
    for (IsoSchemaRef unsafe : new IsoSchemaRef[] {
        reference("../synthetic", "1.0", "guide.xml"), reference("synthetic", "../1.0", "guide.xml"),
        reference("synthetic", "1.0", "../guide.xml"), reference("synthetic", "1.0", schemaFile.toString())}) {
      assertThatThrownBy(() -> codec.validate(unsafe, Set.of(0x0100)))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void rejectsSymlinksEvenWhenTheyPointInsideTheRegistry() throws Exception {
    Files.createSymbolicLink(schemaFile.getParent().resolve("linked.xml"), schemaFile);
    assertThatThrownBy(() -> codec.validate(reference("synthetic", "1.0", "linked.xml"), Set.of(0x0100)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbolic links");
    Path outside = root.getParent().resolve("outside-guide-" + root.getFileName() + ".xml");
    try {
      Files.writeString(outside, SCHEMA);
      Files.createSymbolicLink(schemaFile.getParent().resolve("outside.xml"), outside);
      assertThatThrownBy(() -> codec.validate(reference("synthetic", "1.0", "outside.xml"), Set.of(0x0100)))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escapes");
    } finally {
      Files.deleteIfExists(outside);
    }
  }

  private IsoSchemaRef reference(String id, String version, String file) {
    return new IsoSchemaRef(root.toString(), id, version, "J8583_XML", file);
  }
}
