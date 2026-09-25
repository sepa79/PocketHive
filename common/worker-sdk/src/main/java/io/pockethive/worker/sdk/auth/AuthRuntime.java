package io.pockethive.worker.sdk.auth;

import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Responsibility: compose prepared worker profiles/resources and observe delegated credential
 * application. Must not: load profile documents, construct credentials, acquire OAuth tokens or own
 * product identity. Delegates owned-resource lifetime to RESP-WORK-AUTH-RESOURCES. Consumes
 * RESP-REDIS-CONNECTION-SETTINGS for validated token-store connection values. Contract:
 * RESP-WORK-AUTH-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-work-auth-runtime.
 */
public final class AuthRuntime implements AutoCloseable {
  private static final Duration FAILURE_SUMMARY_INTERVAL = Duration.ofMinutes(5);
  private static final ConcurrentMap<String, FailureState> FAILURES = new ConcurrentHashMap<>();

  private final Map<String, AuthProfile> profiles;
  private final Map<String, String> fingerprints;
  private final TokenStore tokenStore;
  private final AuthRuntimeResources resources;
  private final TemplateRenderer renderer;
  private final HttpClient httpClient;
  private final OAuth2HttpSignatureTokenProvider signedTokens;
  private final OAuth2TokenProvider ordinaryTokens;

  private AuthRuntime(
      Map<String, AuthProfile> profiles,
      Map<String, String> fingerprints,
      AuthRuntimeResources resources,
      TemplateRenderer renderer) {
    try {
      this.profiles = Map.copyOf(profiles);
      this.fingerprints = Map.copyOf(fingerprints);
      this.resources = resources;
      this.tokenStore = resources.tokenStore();
      this.renderer = renderer;
      this.httpClient = resources.httpClient();
      this.ordinaryTokens = new OAuth2TokenProvider(tokenStore, httpClient);
      this.signedTokens = new OAuth2HttpSignatureTokenProvider(tokenStore, httpClient);
    } catch (RuntimeException | Error failure) {
      resources.closeAfterFailure(failure);
      throw failure;
    }
  }

  AuthRuntime(
      Map<String, AuthProfile> profiles,
      Map<String, String> fingerprints,
      TokenStore tokenStore,
      TemplateRenderer renderer,
      HttpClient httpClient) {
    this(profiles, fingerprints, AuthRuntimeResources.borrowed(tokenStore, httpClient), renderer);
  }

  @Override
  public void close() {
    resources.close();
  }

  public static AuthRuntime forTemplates(
      String templateRoot,
      List<AuthRef> refs,
      Map<String, Object> vars,
      WorkerContext context,
      TemplateRenderer renderer,
      RedisSequenceProperties redisProperties) {
    return forTemplates(templateRoot, refs, vars, Map.of(), context, renderer, redisProperties);
  }

  public static AuthRuntime forTemplates(
      String templateRoot,
      List<AuthRef> refs,
      Map<String, Object> vars,
      Map<String, Object> sut,
      WorkerContext context,
      TemplateRenderer renderer,
      RedisSequenceProperties redisProperties) {
    if (refs.isEmpty()) {
      return inactive(renderer);
    }
    Path authProfiles = AuthProfileLoader.forTemplates(templateRoot);
    return fromFile(authProfiles, refs, vars, sut, context, renderer, redisProperties);
  }

  public static AuthRuntime forApplications(
      List<AuthRef> refs,
      Map<String, Object> vars,
      WorkerContext context,
      TemplateRenderer renderer,
      RedisSequenceProperties redisProperties) {
    return forApplications(refs, vars, Map.of(), context, renderer, redisProperties);
  }

  public static AuthRuntime forApplications(
      List<AuthRef> refs,
      Map<String, Object> vars,
      Map<String, Object> sut,
      WorkerContext context,
      TemplateRenderer renderer,
      RedisSequenceProperties redisProperties) {
    if (refs == null || refs.isEmpty()) {
      return inactive(renderer);
    }
    Path authProfiles = AuthProfileLoader.forApplications();
    return fromFile(authProfiles, refs, vars, sut, context, renderer, redisProperties);
  }

  public static AuthRuntime inactive(TemplateRenderer renderer) {
    return new AuthRuntime(Map.of(), Map.of(), AuthRuntimeResources.withoutTokenStore(), renderer);
  }

  public boolean active() {
    return !profiles.isEmpty();
  }

  public void applyHttp(
      AuthRef ref, MutableHttpRequest request, WorkItem item, WorkerContext context) {
    try {
      AuthProfile profile = profile(ref);
      AuthMaterial material = material(ref.profileId(), profile, item, context);
      AuthCredentialApplication.applyHttp(ref, profile, material, request, item);
      context
          .meterRegistry()
          .counter(
              "pockethive.auth.apply",
              "profileId",
              ref.profileId(),
              "applyAs",
              ref.applyAs().name())
          .increment();
      reportRecovery(ref, "HTTP", context);
    } catch (RuntimeException ex) {
      reportFailure(ref, "HTTP", context, ex);
      throw AuthFailureException.application(ref, "HTTP", ex);
    }
  }

  public String applyTcpBody(AuthRef ref, String body, WorkItem item, WorkerContext context) {
    try {
      AuthProfile profile = profile(ref);
      AuthMaterial material = material(ref.profileId(), profile, item, context);
      String result = AuthCredentialApplication.applyTcp(ref, profile, material, body);
      context
          .meterRegistry()
          .counter(
              "pockethive.auth.apply",
              "profileId",
              ref.profileId(),
              "applyAs",
              ref.applyAs().name())
          .increment();
      reportRecovery(ref, "TCP", context);
      return result;
    } catch (RuntimeException ex) {
      reportFailure(ref, "TCP", context, ex);
      throw AuthFailureException.application(ref, "TCP", ex);
    }
  }

  public String applyIsoPayloadHex(
      AuthRef ref, String payloadHex, WorkItem item, WorkerContext context) {
    try {
      AuthProfile profile = profile(ref);
      String result = AuthCredentialApplication.applyIso(ref, profile, payloadHex);
      context
          .meterRegistry()
          .counter(
              "pockethive.auth.apply",
              "profileId",
              ref.profileId(),
              "applyAs",
              ref.applyAs().name())
          .increment();
      reportRecovery(ref, "ISO8583", context);
      return result;
    } catch (RuntimeException ex) {
      reportFailure(ref, "ISO8583", context, ex);
      throw AuthFailureException.application(ref, "ISO8583", ex);
    }
  }

  public Map<String, Object> transportOptions(AuthRef ref, WorkerContext context) {
    try {
      AuthProfile profile = profile(ref);
      Map<String, Object> options = AuthCredentialApplication.transportOptions(ref, profile);
      context
          .meterRegistry()
          .counter(
              "pockethive.auth.apply",
              "profileId",
              ref.profileId(),
              "applyAs",
              ref.applyAs().name())
          .increment();
      reportRecovery(ref, "transport", context);
      return Map.copyOf(options);
    } catch (RuntimeException ex) {
      reportFailure(ref, "transport", context, ex);
      throw AuthFailureException.application(ref, "transport", ex);
    }
  }

  public Map<String, Object> redactedStatus() {
    return Map.of("active", active(), "profiles", profiles.keySet());
  }

  private static AuthRuntime fromFile(
      Path file,
      List<AuthRef> refs,
      Map<String, Object> vars,
      Map<String, Object> sut,
      WorkerContext context,
      TemplateRenderer renderer,
      RedisSequenceProperties redisProperties) {
    try {
      PreparedAuthProfiles prepared =
          AuthProfileLoader.load(file, refs, vars, sut, context, renderer);
      AuthRuntimeResources resources =
          prepared.profiles().values().stream()
                  .anyMatch(profile -> profile.getStorage().getMode() == AuthStorageMode.REDIS)
              ? AuthRuntimeResources.redis(
                  context.info().swarmId(),
                  redisProperties.connectionSettings(RedisSequenceProperties.PREFIX))
              : AuthRuntimeResources.withoutTokenStore();
      return new AuthRuntime(prepared.profiles(), prepared.fingerprints(), resources, renderer);
    } catch (AuthFailureException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw AuthFailureException.configuration(
          "auth-profile-resolution",
          failure.getMessage() == null || failure.getMessage().isBlank()
              ? "Failed to resolve auth profiles from " + file
              : failure.getMessage(),
          failure);
    }
  }

  private AuthProfile profile(AuthRef ref) {
    AuthProfile profile = profiles.get(ref.profileId());
    if (profile == null) {
      throw new IllegalArgumentException("Unknown auth profileId=" + ref.profileId());
    }
    return profile;
  }

  private AuthMaterial material(
      String profileId, AuthProfile profile, WorkItem item, WorkerContext context) {
    if (profile.getType() == AuthType.OAUTH2_HTTP_SIGNATURE) {
      return signedTokens.material(
          profileId,
          AuthProfileFields.tokenKey(profile),
          fingerprints.get(profileId),
          profile,
          context);
    }
    if (profile.getType().requiredStorageMode() == AuthStorageMode.REDIS) {
      return ordinaryTokens.material(profileId, fingerprints.get(profileId), profile, context);
    }
    return AuthCredentialApplication.material(profile, item);
  }

  private static void reportFailure(
      AuthRef ref, String stage, WorkerContext context, RuntimeException ex) {
    String profileId = ref == null ? "unknown" : ref.profileId();
    String applyAs = ref == null || ref.applyAs() == null ? "unknown" : ref.applyAs().name();
    context
        .meterRegistry()
        .counter(
            "pockethive.auth.apply.failure",
            "profileId",
            profileId,
            "applyAs",
            applyAs,
            "stage",
            stage)
        .increment();
    String key =
        context.info().swarmId()
            + ":"
            + context.info().instanceId()
            + ":"
            + stage
            + ":"
            + profileId
            + ":"
            + applyAs;
    long now = System.currentTimeMillis();
    FAILURES.compute(
        key,
        (ignored, state) -> {
          if (state == null) {
            context
                .logger()
                .warn(
                    "Auth failure for profileId={} applyAs={} stage={} errorClass={}",
                    profileId,
                    applyAs,
                    stage,
                    ex.getClass().getSimpleName());
            publishAuthStatus(context, "failure", profileId, applyAs, stage, 1);
            return new FailureState(1, now + FAILURE_SUMMARY_INTERVAL.toMillis());
          }
          int count = state.count + 1;
          if (now >= state.nextSummaryAtMillis) {
            context
                .logger()
                .warn(
                    "Auth failure summary for profileId={} applyAs={} stage={} repeatedFailures={}",
                    profileId,
                    applyAs,
                    stage,
                    count);
            publishAuthStatus(context, "failure-summary", profileId, applyAs, stage, count);
            return new FailureState(count, now + FAILURE_SUMMARY_INTERVAL.toMillis());
          }
          return new FailureState(count, state.nextSummaryAtMillis);
        });
  }

  private static void reportRecovery(AuthRef ref, String stage, WorkerContext context) {
    String profileId = ref == null ? "unknown" : ref.profileId();
    String applyAs = ref == null || ref.applyAs() == null ? "unknown" : ref.applyAs().name();
    String key =
        context.info().swarmId()
            + ":"
            + context.info().instanceId()
            + ":"
            + stage
            + ":"
            + profileId
            + ":"
            + applyAs;
    FailureState previous = FAILURES.remove(key);
    if (previous != null) {
      context
          .logger()
          .info(
              "Auth recovered for profileId={} applyAs={} stage={} previousFailures={}",
              profileId,
              applyAs,
              stage,
              previous.count);
      context
          .meterRegistry()
          .counter(
              "pockethive.auth.apply.recovery",
              "profileId",
              profileId,
              "applyAs",
              applyAs,
              "stage",
              stage)
          .increment();
      publishAuthStatus(context, "recovered", profileId, applyAs, stage, previous.count);
    }
  }

  private static void publishAuthStatus(
      WorkerContext context,
      String status,
      String profileId,
      String applyAs,
      String stage,
      int count) {
    context
        .statusPublisher()
        .update(
            s ->
                s.data(
                    "auth",
                    Map.of(
                        "status", status,
                        "profileId", profileId,
                        "applyAs", applyAs,
                        "stage", stage,
                        "count", count)));
    context.statusPublisher().emitDelta();
  }

  private record FailureState(int count, long nextSummaryAtMillis) {}
}
