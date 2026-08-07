package io.pockethive.requestexecution;

import java.net.URI;
import java.util.Objects;

/**
 * Canonical resolver for a rendered HTTP path and an explicit target base URL.
 *
 * <p>Relative paths are appended to the configured base URL. This deliberately preserves the
 * Processor contract: {@code http://target/api} plus {@code /orders} targets
 * {@code http://target/api/orders}, rather than replacing {@code /api} as {@link URI#resolve(URI)}
 * would do.</p>
 */
public final class HttpTargetResolver {
  private HttpTargetResolver() {
  }

  public static URI resolve(URI baseUri, String path) {
    return resolve(Objects.requireNonNull(baseUri, "baseUri").toString(), path);
  }

  public static URI resolve(String baseUrl, String path) {
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    URI candidate = URI.create(path.trim());
    if (candidate.isAbsolute()) {
      return requireHttp(candidate);
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank for a relative path");
    }
    return requireHttp(URI.create(baseUrl + path.trim()));
  }

  public static boolean isAbsoluteHttpUri(String path) {
    if (path == null || path.isBlank()) {
      return false;
    }
    try {
      return isAbsoluteHttpUri(URI.create(path.trim()));
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static boolean isAbsoluteHttpUri(URI candidate) {
    if (!candidate.isAbsolute()) {
      return false;
    }
    return isHttpScheme(candidate.getScheme());
  }

  private static URI requireHttp(URI candidate) {
    if (!isHttpScheme(candidate.getScheme())) {
      throw new IllegalArgumentException("target must use http or https");
    }
    return candidate;
  }

  private static boolean isHttpScheme(String scheme) {
    return scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
  }
}
