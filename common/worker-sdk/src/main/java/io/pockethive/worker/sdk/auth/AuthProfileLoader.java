package io.pockethive.worker.sdk.auth;

import static io.pockethive.worker.sdk.auth.AuthProfileFields.*;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkerContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: load the selected profile document and prepare one validated activation set. Must
 * not: open token stores, acquire credentials or mutate downstream requests. Contract:
 * RESP-WORK-AUTH-PROFILE-LOADING —
 * docs/architecture/runtime-responsibilities.md#resp-work-auth-profile-loading.
 */
final class AuthProfileLoader {
  private static final ObjectMapper YAML =
      new ObjectMapper(
              YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .findAndRegisterModules();
  private static final String AUTH_PROFILES_FILE = "authProfiles.yaml";
  private static final String SCENARIO_ROOT_PROPERTY = "pockethive.scenario.root";
  private static final String SCENARIO_ROOT_ENV = "POCKETHIVE_SCENARIO_ROOT";

  static PreparedAuthProfiles load(
      Path file,
      List<AuthRef> refs,
      Map<String, Object> vars,
      Map<String, Object> sut,
      WorkerContext context,
      TemplateRenderer renderer) {
    try {
      AuthProfileDocument raw = YAML.readValue(file.toFile(), AuthProfileDocument.class);
      Map<String, AuthProfile> resolved = new LinkedHashMap<>();
      Map<String, String> fingerprints = new LinkedHashMap<>();
      Map<String, String> tokenFingerprints = new LinkedHashMap<>();
      for (AuthRef ref : refs) {
        AuthProfile profile = raw.profiles().get(ref.profileId());
        if (profile == null) {
          throw new IllegalArgumentException(
              "authRef.profileId '" + ref.profileId() + "' not found in " + file);
        }
        if (AuthProfilePreparation.referencesSut(profile) && (sut == null || sut.isEmpty())) {
          throw new IllegalArgumentException(
              "authRef.profileId '"
                  + ref.profileId()
                  + "' references sut but no SUT context was provided");
        }
        AuthProfile resolvedProfile =
            AuthProfilePreparation.resolveProfile(profile, vars, sut, context, renderer);
        AuthProfilePreparation.validateProfile(ref.profileId(), resolvedProfile);
        String fingerprint = AuthProfilePreparation.fingerprint(resolvedProfile);
        resolved.put(ref.profileId(), resolvedProfile);
        fingerprints.put(ref.profileId(), fingerprint);
        String tokenKey = tokenKey(resolvedProfile);
        if (tokenKey != null) {
          String previous = tokenFingerprints.putIfAbsent(tokenKey, fingerprint);
          if (previous != null && !previous.equals(fingerprint)) {
            throw new IllegalArgumentException(
                "authProfiles.yaml declares tokenKey '" + tokenKey + "' with multiple configs");
          }
        }
      }
      return new PreparedAuthProfiles(resolved, fingerprints);
    } catch (IOException ex) {
      throw AuthFailureException.configuration("auth-profiles-read", "Failed to read " + file, ex);
    } catch (RuntimeException ex) {
      if (ex instanceof AuthFailureException authFailure) {
        throw authFailure;
      }
      throw AuthFailureException.configuration(
          "auth-profile-resolution",
          ex.getMessage() == null || ex.getMessage().isBlank()
              ? "Failed to resolve auth profiles from " + file
              : ex.getMessage(),
          ex);
    }
  }

  static Path forApplications() {
    Path file = scenarioRoot().resolve(AUTH_PROFILES_FILE);
    if (!Files.isRegularFile(file)) {
      throw AuthFailureException.configuration(
          "missing-auth-profiles",
          "Request declares processor-stage auth but " + file + " was not found",
          null);
    }
    return file;
  }

  static Path forTemplates(String templateRoot) {
    List<Path> candidates = new ArrayList<>();
    if (templateRoot != null && !templateRoot.isBlank()) {
      Path cursor = Path.of(templateRoot).toAbsolutePath().normalize();
      while (cursor != null) {
        candidates.add(cursor.resolve(AUTH_PROFILES_FILE));
        cursor = cursor.getParent();
      }
    }
    candidates.add(Path.of("/app/scenario").resolve(AUTH_PROFILES_FILE));
    for (Path candidate : candidates) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw AuthFailureException.configuration(
        "missing-auth-profiles",
        "Templates declare authRef but authProfiles.yaml was not found for templateRoot="
            + templateRoot,
        null);
  }

  private static Path scenarioRoot() {
    String configured = System.getProperty(SCENARIO_ROOT_PROPERTY);
    if (configured == null || configured.isBlank()) {
      configured = System.getenv(SCENARIO_ROOT_ENV);
    }
    if (configured == null || configured.isBlank()) {
      configured = "/app/scenario";
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }
}
