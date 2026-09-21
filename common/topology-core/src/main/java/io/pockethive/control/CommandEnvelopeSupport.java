package io.pockethive.control;

final class CommandEnvelopeSupport {

  private CommandEnvelopeSupport() {
  }

  static String requireCurrentVersion(String version) {
    String value = requireText("version", version);
    if (!ControlPlaneEnvelopeVersion.CURRENT.equals(value)) {
      throw new IllegalArgumentException(
          "version must be " + ControlPlaneEnvelopeVersion.CURRENT + ", got " + value);
    }
    return value;
  }

  static String requireKind(String expected, String actual) {
    String value = requireText("kind", actual);
    if (!expected.equals(value)) {
      throw new IllegalArgumentException("kind must be '" + expected + "'");
    }
    return value;
  }

  static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
