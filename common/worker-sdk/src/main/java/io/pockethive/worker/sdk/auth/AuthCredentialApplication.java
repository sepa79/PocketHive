package io.pockethive.worker.sdk.auth;

import static io.pockethive.worker.sdk.auth.AuthProfileFields.*;

import io.pockethive.work.api.WorkItem;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Responsibility: construct and apply credential bytes to the selected downstream protocol. Must
 * not: perform profile/token IO, select storage, sign OAuth acquisition or own failure telemetry.
 * Contract: RESP-WORK-AUTH-APPLICATION —
 * docs/architecture/runtime-responsibilities.md#resp-work-auth-application.
 */
final class AuthCredentialApplication {
  static void applyHttp(
      AuthRef ref,
      AuthProfile profile,
      AuthMaterial material,
      MutableHttpRequest request,
      WorkItem item) {
    switch (ref.applyAs()) {
      case HTTP_AUTHORIZATION_BEARER ->
          AuthHttpHeaders.replace(request.headers(), "Authorization", "Bearer " + material.value());
      case HTTP_HEADER, HMAC_HEADER ->
          AuthHttpHeaders.replace(
              request.headers(),
              headerName(ref, profile),
              headerValue(ref, profile, material, item, request.body()));
      case HTTP_QUERY_PARAM ->
          request.setPath(appendQuery(request.path(), queryParam(ref, profile), material.value()));
      default -> throw unsupported(ref, "HTTP");
    }
  }

  static String applyTcp(AuthRef ref, AuthProfile profile, AuthMaterial material, String body) {
    return switch (ref.applyAs()) {
      case TCP_PAYLOAD_PREFIX -> material.value() + (body == null ? "" : body);
      case HMAC_PAYLOAD_FIELD ->
          appendField(body, fieldName(ref, profile), hmacHex(profile, body == null ? "" : body));
      default -> throw unsupported(ref, "TCP request-builder");
    };
  }

  static String applyIso(AuthRef ref, AuthProfile profile, String payloadHex) {
    if (ref.applyAs() != AuthApplyAs.ISO8583_MAC_FIELD) throw unsupported(ref, "ISO8583 processor");
    return payloadHex
        + macHex(profile, HexFormat.of().parseHex(payloadHex)).toUpperCase(Locale.ROOT);
  }

  static Map<String, Object> transportOptions(AuthRef ref, AuthProfile profile) {
    if (ref.applyAs() != AuthApplyAs.MTLS_CLIENT_CERT
        || profile.getType() != AuthType.TLS_CLIENT_CERT) throw unsupported(ref, "transport");
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("ssl", true);
    options.put("keyStorePath", required(profile, "keyStorePath"));
    String password = optional(profile, "keyStorePassword");
    if (password != null) options.put("keyStorePassword", password);
    options.put(
        "keyStoreType",
        optional(profile, "keyStoreType") == null ? "PKCS12" : optional(profile, "keyStoreType"));
    return Map.copyOf(options);
  }

  static AuthMaterial material(AuthProfile profile, WorkItem item) {
    return switch (profile.getType()) {
      case BEARER_TOKEN, STATIC_TOKEN ->
          new AuthMaterial(required(profile, "token"), "Bearer", null, null);
      case API_KEY -> new AuthMaterial(required(profile, "key"), "ApiKey", null, null);
      case BASIC_AUTH -> new AuthMaterial(basicValue(profile), "Basic", null, null);
      case HMAC_SIGNATURE ->
          new AuthMaterial(
              hmacHex(profile, item == null ? "" : item.payload()), "HMAC", null, null);
      case AWS_SIGNATURE_V4 ->
          new AuthMaterial(
              awsAuthorization(profile, item == null ? "" : item.payload()), "AWS4", null, null);
      case MESSAGE_FIELD_AUTH -> new AuthMaterial(required(profile, "value"), "Field", null, null);
      case ISO8583_MAC -> new AuthMaterial(required(profile, "macKey"), "MAC", null, null);
      case TLS_CLIENT_CERT ->
          new AuthMaterial(required(profile, "keyStorePath"), "mTLS", null, null);
      default ->
          throw new IllegalArgumentException(
              "Unsupported non-refresh auth type " + profile.getType());
    };
  }

  private static String headerName(AuthRef ref, AuthProfile profile) {
    String name = ref.headerName() == null ? optional(profile, "headerName") : ref.headerName();
    return name == null ? "Authorization" : name;
  }

  private static String queryParam(AuthRef ref, AuthProfile profile) {
    String name = ref.queryParam() == null ? optional(profile, "queryParam") : ref.queryParam();
    if (name == null) {
      throw new IllegalArgumentException(
          "HTTP_QUERY_PARAM auth requires authRef.queryParam or profile queryParam");
    }
    return name;
  }

  private static String fieldName(AuthRef ref, AuthProfile profile) {
    String name = ref.targetField() == null ? optional(profile, "targetField") : ref.targetField();
    return name == null ? "auth" : name;
  }

  private static String headerValue(
      AuthRef ref, AuthProfile profile, AuthMaterial material, WorkItem item, String body) {
    return switch (profile.getType()) {
      case BASIC_AUTH -> "Basic " + material.value();
      case BEARER_TOKEN, STATIC_TOKEN, OAUTH2_CLIENT_CREDENTIALS, OAUTH2_PASSWORD_GRANT ->
          material.value();
      case API_KEY -> material.value();
      case HMAC_SIGNATURE -> hmacHex(profile, body == null ? "" : body);
      case AWS_SIGNATURE_V4 -> awsAuthorization(profile, body == null ? "" : body);
      default -> material.value();
    };
  }

  private static String basicValue(AuthProfile profile) {
    String raw = required(profile, "username") + ":" + required(profile, "password");
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static String hmacHex(AuthProfile profile, String payload) {
    try {
      String algorithm =
          optional(profile, "algorithm") == null ? "HmacSHA256" : optional(profile, "algorithm");
      Mac mac = Mac.getInstance(algorithm);
      mac.init(
          new SecretKeySpec(
              required(profile, "secretKey").getBytes(StandardCharsets.UTF_8), algorithm));
      return HexFormat.of()
          .formatHex(
              mac.doFinal((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to compute HMAC auth", ex);
    }
  }

  private static String macHex(AuthProfile profile, byte[] payload) {
    try {
      Mac mac =
          Mac.getInstance(
              optional(profile, "algorithm") == null
                  ? "HmacSHA256"
                  : optional(profile, "algorithm"));
      mac.init(
          new SecretKeySpec(
              required(profile, "macKey").getBytes(StandardCharsets.UTF_8), mac.getAlgorithm()));
      return HexFormat.of().formatHex(mac.doFinal(payload));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to compute ISO8583 MAC", ex);
    }
  }

  private static String awsAuthorization(AuthProfile profile, String payload) {
    String accessKeyId = required(profile, "accessKeyId");
    String secretAccessKey = required(profile, "secretAccessKey");
    String region = required(profile, "region");
    String service = required(profile, "service");
    String signature =
        sha256Hex(
            accessKeyId + ":" + secretAccessKey + ":" + region + ":" + service + ":" + payload);
    return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + ", Signature=" + signature;
  }

  private static String appendQuery(String path, String name, String value) {
    String separator = path.contains("?") ? "&" : "?";
    return path + separator + url(name) + "=" + url(value);
  }

  private static String appendField(String body, String name, String value) {
    String prefix = body == null ? "" : body;
    if (prefix.isBlank()) {
      return name + "=" + value;
    }
    return prefix + "\n" + name + "=" + value;
  }

  private static String url(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to hash auth value", ex);
    }
  }

  private static IllegalArgumentException unsupported(AuthRef ref, String stage) {
    return new IllegalArgumentException(
        "authRef.applyAs " + ref.applyAs() + " is not supported at " + stage);
  }
}
