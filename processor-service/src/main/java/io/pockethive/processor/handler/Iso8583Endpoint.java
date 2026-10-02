package io.pockethive.processor.handler;

import java.net.URI;
import java.util.Locale;

/**
 * Responsibility: parse the explicit ISO execution URI and expose its one endpoint selection.
 * Must not: bind listeners, open clients or resolve another copy of MIP settings.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute;
 * RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public record Iso8583Endpoint(String scheme, String host, int port) {
  public static final String TCP_SCHEME = "tcp";
  public static final String TCPS_SCHEME = "tcps";
  public static final String MIP_SCHEME = "mip";

  public Iso8583Endpoint {
    if (!TCP_SCHEME.equals(scheme) && !TCPS_SCHEME.equals(scheme) && !MIP_SCHEME.equals(scheme)) {
      throw invalidEndpoint();
    }
    if (host == null || host.isBlank() || port <= 0 || port > 65535) {
      throw invalidEndpoint();
    }
  }

  public static Iso8583Endpoint parse(String baseUrl) {
    URI uri = parseUri(baseUrl);
    if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
        || (uri.getPath() != null && !uri.getPath().isEmpty())) {
      throw invalidEndpoint();
    }
    return new Iso8583Endpoint(scheme(uri), uri.getHost(), uri.getPort());
  }

  public static boolean selectsMipServer(String baseUrl) {
    return MIP_SCHEME.equals(scheme(parseUri(baseUrl)));
  }

  public boolean mipServer() {
    return MIP_SCHEME.equals(scheme);
  }

  public boolean tls() {
    return TCPS_SCHEME.equals(scheme);
  }

  public String endpoint() {
    return scheme + "://" + host + ":" + port;
  }

  private static URI parseUri(String baseUrl) {
    if (baseUrl == null || baseUrl.isBlank()) {
      throw invalidEndpoint();
    }
    try {
      return URI.create(baseUrl.trim());
    } catch (IllegalArgumentException ex) {
      throw invalidEndpoint();
    }
  }

  private static String scheme(URI uri) {
    return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
  }

  private static IllegalArgumentException invalidEndpoint() {
    return new IllegalArgumentException("invalid ISO8583 baseUrl");
  }
}
