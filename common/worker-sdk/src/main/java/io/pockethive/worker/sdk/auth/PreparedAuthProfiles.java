package io.pockethive.worker.sdk.auth;

import java.util.Map;

/**
 * Responsibility: carry a prepared profile activation set to resource composition. Must not:
 * discover files, coordinate tokens or expose mutable maps. Contract:
 * RESP-WORK-AUTH-PROFILE-LOADING —
 * docs/architecture/runtime-responsibilities.md#resp-work-auth-profile-loading.
 */
record PreparedAuthProfiles(Map<String, AuthProfile> profiles, Map<String, String> fingerprints) {
  PreparedAuthProfiles {
    profiles = Map.copyOf(profiles);
    fingerprints = Map.copyOf(fingerprints);
  }
}
