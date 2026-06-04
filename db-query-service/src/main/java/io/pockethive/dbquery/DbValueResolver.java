package io.pockethive.dbquery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.WorkItem;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

class DbValueResolver {

  private static final TypeReference<LinkedHashMap<String, Object>> STRING_OBJECT_MAP =
      new TypeReference<>() {
      };

  private final ObjectMapper mapper;
  private final JsonNode payload;
  private final Map<String, Object> headers;
  private final Map<String, Object> vars;

  DbValueResolver(ObjectMapper mapper, WorkItem item, Map<String, Object> vars) {
    this.mapper = mapper;
    this.payload = parsePayload(item);
    this.headers = item == null ? Map.of() : item.headers();
    this.vars = vars == null ? Map.of() : Map.copyOf(vars);
  }

  Map<String, Object> payloadAsMap() {
    if (payload != null && payload.isObject()) {
      return mapper.convertValue(payload, STRING_OBJECT_MAP);
    }
    return new LinkedHashMap<>();
  }

  Object resolve(DbQueryTemplate.Param param) {
    Object raw = resolveSource(param.source());
    if (raw == null) {
      throw new IllegalArgumentException("Missing DB query param source " + param.source() + " for " + param.name());
    }
    return convert(raw, param.type(), param.name());
  }

  private Object resolveSource(String source) {
    int dot = source.indexOf('.');
    if (dot <= 0 || dot == source.length() - 1) {
      throw new IllegalArgumentException("Unsupported DB query param source: " + source);
    }
    String root = source.substring(0, dot);
    String path = source.substring(dot + 1);
    return switch (root) {
      case DbQueryConstants.SOURCE_PAYLOAD -> resolveJsonPath(payload, path);
      case DbQueryConstants.SOURCE_HEADERS -> resolveMapPath(headers, path);
      case DbQueryConstants.SOURCE_VARS -> resolveMapPath(vars, path);
      default -> throw new IllegalArgumentException("Unsupported DB query param source root: " + root);
    };
  }

  private Object resolveJsonPath(JsonNode node, String dottedPath) {
    if (node == null || dottedPath == null || dottedPath.isBlank()) {
      return null;
    }
    JsonNode current = node;
    for (String part : dottedPath.split("\\.")) {
      current = current == null ? null : current.get(part);
      if (current == null || current.isMissingNode() || current.isNull()) {
        return null;
      }
    }
    return mapper.convertValue(current, Object.class);
  }

  @SuppressWarnings("unchecked")
  private Object resolveMapPath(Map<String, Object> map, String dottedPath) {
    Object current = map;
    for (String part : dottedPath.split("\\.")) {
      if (!(current instanceof Map<?, ?> currentMap)) {
        return null;
      }
      current = ((Map<String, Object>) currentMap).get(part);
      if (current == null) {
        return null;
      }
    }
    return current;
  }

  private Object convert(Object raw, DbQueryTemplate.ParamType type, String paramName) {
    try {
      return switch (type) {
        case STRING -> raw.toString();
        case INT -> raw instanceof Number n ? n.intValue() : Integer.parseInt(raw.toString());
        case LONG -> raw instanceof Number n ? n.longValue() : Long.parseLong(raw.toString());
        case DOUBLE -> raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString());
        case DECIMAL -> raw instanceof BigDecimal b ? b : new BigDecimal(raw.toString());
        case BOOL -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
        case DATE -> raw instanceof Date d ? d : Date.valueOf(LocalDate.parse(raw.toString()));
        case TIMESTAMP -> toTimestamp(raw);
      };
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Failed to convert DB query param " + paramName + " to " + type, ex);
    }
  }

  private Timestamp toTimestamp(Object raw) {
    if (raw instanceof Timestamp timestamp) {
      return timestamp;
    }
    String text = raw.toString();
    try {
      return Timestamp.from(Instant.parse(text));
    } catch (RuntimeException ignored) {
      return Timestamp.valueOf(LocalDateTime.parse(text));
    }
  }

  private JsonNode parsePayload(WorkItem item) {
    if (item == null || item.payload() == null || item.payload().isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(item.payload());
    } catch (Exception ex) {
      throw new IllegalArgumentException("DB query worker requires JSON object input payload", ex);
    }
  }
}
