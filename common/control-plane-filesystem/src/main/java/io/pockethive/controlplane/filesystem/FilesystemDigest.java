package io.pockethive.controlplane.filesystem;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class FilesystemDigest {

  private FilesystemDigest() {
  }

  static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static String requireSha256(String value, String field) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalStateException(field + " must be a lowercase SHA-256 digest");
    }
    return value;
  }
}
