package io.pockethive.iso8583;

import com.solab.iso8583.parse.ConfigParser;
import io.pockethive.work.api.IsoSchemaRef;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Responsibility: snapshot explicit immutable ISO schema packs once and cache each thread's J8583 factory.
 * Must not: own another schema guide parser, network effects or session state.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
final class Iso8583SchemaPackRegistry {
  private static final String J8583_XML = "J8583_XML";
  private final Map<IsoSchemaRef, ThreadLocal<SchemaMessageFactory>> factories = new ConcurrentHashMap<>();

  SchemaMessageFactory resolve(IsoSchemaRef reference) {
    Objects.requireNonNull(reference, "schemaRef");
    if (!J8583_XML.equals(reference.schemaAdapter())) {
      throw new IllegalArgumentException("Unsupported ISO schema adapter: " + reference.schemaAdapter());
    }
    return factories.computeIfAbsent(reference, this::loadSnapshot).get();
  }

  private Path resolvePath(IsoSchemaRef reference) {
    validateSegment(reference.schemaId(), "schemaId");
    validateSegment(reference.schemaVersion(), "schemaVersion");
    Path relativeFile = Path.of(reference.schemaFile());
    if (relativeFile.isAbsolute() || relativeFile.getNameCount() == 0) {
      throw new IllegalArgumentException("schemaFile must be a relative pack path");
    }
    for (Path segment : relativeFile) {
      validateSegment(segment.toString(), "schemaFile");
    }
    Path root = Path.of(reference.schemaRegistryRoot()).toAbsolutePath().normalize();
    Path path = root.resolve(reference.schemaId()).resolve(reference.schemaVersion()).resolve(relativeFile);
    try {
      Path realRoot = root.toRealPath();
      Path realPack = root.resolve(reference.schemaId()).resolve(reference.schemaVersion()).toRealPath();
      Path realFile = path.toRealPath();
      if (!realPack.startsWith(realRoot) || !realFile.startsWith(realPack)) {
        throw new IllegalArgumentException("ISO schema path escapes its registry or selected pack");
      }
      // Immutable pack paths must not redirect through links, even to another pack in the registry.
      Path cursor = root;
      for (Path segment : root.relativize(path)) {
        cursor = cursor.resolve(segment);
        if (Files.isSymbolicLink(cursor)) {
          throw new IllegalArgumentException("ISO schema pack paths must not contain symbolic links");
        }
      }
      if (!Files.isRegularFile(realFile)) {
        throw new IllegalArgumentException("ISO schema is not a regular file");
      }
      return realFile;
    } catch (IOException ex) {
      throw new IllegalStateException("ISO schema pack not found: " + path, ex);
    }
  }

  private void validateSegment(String value, String field) {
    if (value.isBlank() || ".".equals(value) || "..".equals(value)
        || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
        || value.indexOf(':') >= 0 || Path.of(value).isAbsolute()) {
      throw new IllegalArgumentException("Invalid ISO schema path segment: " + field);
    }
  }

  private ThreadLocal<SchemaMessageFactory> loadSnapshot(IsoSchemaRef reference) {
    Path path = resolvePath(reference);
    try {
      String xml = Files.readString(path, StandardCharsets.UTF_8);
      var document = SecureIsoXml.parse(xml);
      if (!"j8583-config".equals(document.getDocumentElement().getTagName())) {
        throw new IllegalArgumentException("ISO schema root must be j8583-config");
      }
      // Validate the canonical guides before publishing the immutable XML/path snapshot.
      SchemaMessageFactory initialFactory = createFactory(xml, path);
      ThreadLocal<SchemaMessageFactory> holder =
          ThreadLocal.withInitial(() -> createFactory(xml, path));
      holder.set(initialFactory);
      return holder;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to load ISO schema pack: " + path);
    }
  }

  private SchemaMessageFactory createFactory(String xml, Path path) {
    try {
      var factory = new SchemaMessageFactory();
      ConfigParser.configureFromReader(factory, new StringReader(xml));
      factory.prepareGuides();
      return factory;
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to configure ISO schema snapshot: " + path);
    }
  }
}
