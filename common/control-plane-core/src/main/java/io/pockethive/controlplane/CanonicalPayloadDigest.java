package io.pockethive.controlplane;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Stable SHA-256 identity for an applied JSON-compatible control payload. */
public final class CanonicalPayloadDigest {

  private CanonicalPayloadDigest() {
  }

  public static String sha256(ObjectMapper mapper, Object payload) {
    Objects.requireNonNull(mapper, "mapper");
    try {
      ObjectMapper canonical = mapper.copy()
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
      byte[] json = canonical.writeValueAsBytes(payload == null ? java.util.Map.of() : payload);
      return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Applied control payload is not JSON-serializable", exception);
    }
  }
}
