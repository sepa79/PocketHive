package io.pockethive.clearingexport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record ClearingStructuredSchema(
    String schemaId,
    String schemaVersion,
    String outputFormat,
    String fileNameTemplate,
    Map<String, StructuredFieldRule> recordMapping,
    Map<String, String> headerMapping,
    Map<String, String> footerMapping,
    XmlOutputConfig xml
) {

  ClearingStructuredSchema {
    schemaId = requireText(schemaId, "schemaId");
    schemaVersion = requireText(schemaVersion, "schemaVersion");
    outputFormat = defaultIfBlank(outputFormat, "xml");
    if (!"xml".equalsIgnoreCase(outputFormat)) {
      throw new IllegalArgumentException("Only xml outputFormat is currently supported");
    }
    fileNameTemplate = requireText(fileNameTemplate, "fileNameTemplate");
    recordMapping = normalizeRecordMapping(recordMapping);
    headerMapping = normalizeStringMapping(headerMapping);
    footerMapping = normalizeStringMapping(footerMapping);
    xml = xml == null ? XmlOutputConfig.defaults() : xml.normalize();
  }

  private static Map<String, StructuredFieldRule> normalizeRecordMapping(
      Map<String, StructuredFieldRule> mapping
  ) {
    if (mapping == null || mapping.isEmpty()) {
      throw new IllegalArgumentException("recordMapping must be configured for structured mode");
    }
    Map<String, StructuredFieldRule> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, StructuredFieldRule> entry : mapping.entrySet()) {
      String key = requireText(entry.getKey(), "recordMapping key");
      StructuredFieldRule rule = Objects.requireNonNull(entry.getValue(),
          "recordMapping[" + key + "] must not be null");
      normalized.put(key, rule.normalize());
    }
    return Map.copyOf(normalized);
  }

  private static Map<String, String> normalizeStringMapping(Map<String, String> mapping) {
    if (mapping == null || mapping.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : mapping.entrySet()) {
      normalized.put(requireText(entry.getKey(), "mapping key"),
          requireText(entry.getValue(), "mapping value"));
    }
    return Map.copyOf(normalized);
  }

  private static String requireText(String value, String name) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value.trim();
  }

  private static String defaultIfBlank(String value, String defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return value.trim();
  }

  record StructuredFieldRule(String expression, Boolean required, String type) {

    StructuredFieldRule normalize() {
      String normalizedType = type == null || type.isBlank() ? "string" : type.trim().toLowerCase();
      if (!normalizedType.equals("string")
          && !normalizedType.equals("long")
          && !normalizedType.equals("decimal")) {
        throw new IllegalArgumentException("Unsupported field type: " + normalizedType);
      }
      return new StructuredFieldRule(
          requireText(expression, "recordMapping expression"),
          required == null ? Boolean.TRUE : required,
          normalizedType);
    }

    boolean requiredFlag() {
      return required == null || required;
    }
  }

  record XmlOutputConfig(
      Boolean declaration,
      String encoding,
      String rootElement,
      String headerElement,
      String recordsElement,
      String recordElement,
      String footerElement,
      String namespaceUri,
      String namespacePrefix,
      String recordNamespaceUri,
      String recordNamespacePrefix,
      Boolean indent
  ) {

    static XmlOutputConfig defaults() {
      return new XmlOutputConfig(
          true,
          "UTF-8",
          "Document",
          "FileHeader",
          "Transactions",
          "Transaction",
          "FileTrailer",
          "",
          "",
          "",
          "",
          false
      );
    }

    XmlOutputConfig normalize() {
      return new XmlOutputConfig(
          declaration == null ? true : declaration,
          defaultIfBlank(encoding, "UTF-8"),
          defaultIfBlank(rootElement, "Document"),
          defaultIfBlank(headerElement, "FileHeader"),
          defaultIfBlank(recordsElement, "Transactions"),
          defaultIfBlank(recordElement, "Transaction"),
          defaultIfBlank(footerElement, "FileTrailer"),
          namespaceUri == null ? "" : namespaceUri.trim(),
          namespacePrefix == null ? "" : namespacePrefix.trim(),
          recordNamespaceUri == null ? "" : recordNamespaceUri.trim(),
          recordNamespacePrefix == null ? "" : recordNamespacePrefix.trim(),
          indent == null ? false : indent
      );
    }
  }
}

