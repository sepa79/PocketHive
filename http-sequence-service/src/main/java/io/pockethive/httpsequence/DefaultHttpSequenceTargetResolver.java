package io.pockethive.httpsequence;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DefaultHttpSequenceTargetResolver implements HttpSequenceTargetResolver {

  private static final Set<String> HTTP_SCHEMES = Set.of("http", "https");
  private static final Set<String> HTTP_ENDPOINT_KINDS = Set.of("HTTP", "HTTPS");
  private static final String ENDPOINTS_FIELD = "endpoints";
  private static final String KIND_FIELD = "kind";
  private static final String BASE_URL_FIELD = "baseUrl";
  private static final Pattern PERCENT_OCTET = Pattern.compile("%([0-9A-Fa-f]{2})");

  @Override
  public List<BaseTarget> resolveBases(HttpSequenceWorkerConfig config) {
    Objects.requireNonNull(config, "config");
    URI workerBaseUri = parseBaseUri(config.baseUrl(), "baseUrl");
    Map<String, Object> sut = config.authProfileSutContext();
    List<BaseTarget> targets = new ArrayList<>(config.steps().size());

    for (int index = 0; index < config.steps().size(); index++) {
      HttpSequenceWorkerConfig.Step step = config.steps().get(index);
      if (step.baseUrl() != null) {
        targets.add(new BaseTarget(
            parseBaseUri(step.baseUrl(), "steps[" + index + "].baseUrl"),
            TargetSource.STEP_BASE_URL,
            null));
      } else if (step.sutEndpointId() != null) {
        targets.add(resolveSutEndpoint(sut, step.sutEndpointId(), index));
      } else {
        targets.add(new BaseTarget(workerBaseUri, TargetSource.WORKER_BASE_URL, null));
      }
    }
    return List.copyOf(targets);
  }

  @Override
  public ResolvedTarget resolve(BaseTarget baseTarget, String renderedPath) {
    Objects.requireNonNull(baseTarget, "baseTarget");
    URI reference = parseRenderedPath(renderedPath);
    String rawPath = reference.getRawPath();
    String basePath = baseTarget.baseUri().getRawPath();
    String targetPath = joinPath(basePath, rawPath);
    String query = reference.getRawQuery() == null ? "" : "?" + reference.getRawQuery();
    URI target = URI.create(baseTarget.baseUri().getScheme() + "://"
        + baseTarget.baseUri().getRawAuthority() + targetPath + query);
    return new ResolvedTarget(target, baseTarget.source(), baseTarget.sutEndpointId());
  }

  private static BaseTarget resolveSutEndpoint(Map<String, Object> sut, String endpointId, int stepIndex) {
    String field = "steps[" + stepIndex + "].sutEndpointId";
    if (sut.isEmpty()) {
      throw new IllegalArgumentException(field + " requires an enriched selected-SUT context");
    }
    Object endpointsValue = sut.get(ENDPOINTS_FIELD);
    if (!(endpointsValue instanceof Map<?, ?> endpoints)) {
      throw new IllegalArgumentException(field + " requires selected SUT endpoints");
    }
    Object endpointValue = endpoints.get(endpointId);
    if (!(endpointValue instanceof Map<?, ?> endpoint)) {
      throw new IllegalArgumentException(field + " references unknown SUT endpoint '" + endpointId + "'");
    }

    String kind = stringValue(endpoint.get(KIND_FIELD));
    if (kind == null || !HTTP_ENDPOINT_KINDS.contains(kind.toUpperCase(Locale.ROOT))) {
      throw new IllegalArgumentException(field + " must reference an HTTP or HTTPS endpoint");
    }
    String baseUrl = stringValue(endpoint.get(BASE_URL_FIELD));
    if (baseUrl == null) {
      throw new IllegalArgumentException(field + " endpoint baseUrl is required");
    }
    URI baseUri = parseBaseUri(baseUrl, field + " endpoint baseUrl");
    if (!kind.equalsIgnoreCase(baseUri.getScheme())) {
      throw new IllegalArgumentException(
          field + " endpoint kind " + kind + " does not match baseUrl scheme " + baseUri.getScheme());
    }
    return new BaseTarget(baseUri, TargetSource.SUT_ENDPOINT, endpointId);
  }

  private static URI parseBaseUri(String value, String field) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(field + " must be a valid absolute HTTP(S) base URI", ex);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException(field + " must use http or https");
    }
    if (uri.getRawAuthority() == null || uri.getHost() == null) {
      throw new IllegalArgumentException(field + " must include a valid host");
    }
    if (uri.getPort() > 65_535) {
      throw new IllegalArgumentException(field + " must use a valid port");
    }
    if (uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException(field + " must not include user-info");
    }
    if (uri.getRawQuery() != null) {
      throw new IllegalArgumentException(field + " must not include a query");
    }
    if (uri.getRawFragment() != null) {
      throw new IllegalArgumentException(field + " must not include a fragment");
    }
    if (!uri.normalize().equals(uri) || containsTraversal(uri.getRawPath())) {
      throw new IllegalArgumentException(field + " must not contain path traversal segments");
    }
    return uri;
  }

  private static URI parseRenderedPath(String value) {
    String path = value == null ? "" : value;
    URI reference;
    try {
      reference = URI.create(path);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Rendered HTTP path must be a valid URI reference", ex);
    }
    if (reference.isAbsolute() || reference.getRawAuthority() != null || path.startsWith("//")) {
      throw new IllegalArgumentException("Rendered HTTP path must not select an authority");
    }
    if (reference.getRawFragment() != null) {
      throw new IllegalArgumentException("Rendered HTTP path must not include a fragment");
    }
    rejectTraversal(reference.getRawPath());
    return reference;
  }

  private static String joinPath(String basePath, String renderedPath) {
    String base = basePath == null ? "" : basePath;
    String rendered = renderedPath == null ? "" : renderedPath;
    if (rendered.isEmpty()) {
      return base.isEmpty() ? "/" : base;
    }
    String relative = rendered.startsWith("/") ? rendered.substring(1) : rendered;
    String prefix;
    if (base.isEmpty() || "/".equals(base)) {
      prefix = "/";
    } else {
      prefix = base.endsWith("/") ? base : base + "/";
    }
    return prefix + relative;
  }

  private static void rejectTraversal(String rawPath) {
    if (containsTraversal(rawPath)) {
      throw new IllegalArgumentException("Rendered HTTP path must not contain traversal segments");
    }
  }

  private static boolean containsTraversal(String rawPath) {
    if (rawPath == null || rawPath.isEmpty()) {
      return false;
    }
    String decodedPath = rawPath;
    String previousPath;
    do {
      previousPath = decodedPath;
      decodedPath = decodePercentOctets(previousPath);
    } while (!decodedPath.equals(previousPath));

    for (String segment : decodedPath.replace('\\', '/').split("/", -1)) {
      if (".".equals(segment) || "..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private static String decodePercentOctets(String value) {
    return PERCENT_OCTET.matcher(value).replaceAll(result -> Matcher.quoteReplacement(
        Character.toString((char) Integer.parseInt(result.group(1), 16))));
  }

  private static String stringValue(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    String trimmed = text.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
