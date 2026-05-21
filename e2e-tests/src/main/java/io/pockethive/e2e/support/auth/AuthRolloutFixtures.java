package io.pockethive.e2e.support.auth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.pockethive.e2e.clients.AuthServiceClient;

/**
 * Shared auth rollout users used by the ingress auth pack.
 */
public final class AuthRolloutFixtures {

  public static final UUID BUNDLE_RUNNER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
  public static final UUID E2E_FOLDER_ADMIN_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  public static final UUID BUNDLES_FOLDER_ADMIN_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");

  private final AuthServiceClient adminAuthServiceClient;

  public AuthRolloutFixtures(AuthServiceClient adminAuthServiceClient) {
    this.adminAuthServiceClient = Objects.requireNonNull(adminAuthServiceClient, "adminAuthServiceClient");
  }

  public void provisionBundleRunner() {
    adminAuthServiceClient.upsertUser(BUNDLE_RUNNER_ID, "local-bundle-runner", "Local Bundle Runner", true);
    adminAuthServiceClient.replaceGrants(BUNDLE_RUNNER_ID, List.of(
        grant("POCKETHIVE", "VIEW", "PH_DEPLOYMENT", "*"),
        grant("POCKETHIVE", "RUN", "PH_BUNDLE", "e2e/local-rest")));
  }

  public void provisionE2eFolderAdmin() {
    adminAuthServiceClient.upsertUser(E2E_FOLDER_ADMIN_ID, "local-e2e-folder-admin", "Local E2E Folder Admin", true);
    adminAuthServiceClient.replaceGrants(E2E_FOLDER_ADMIN_ID, List.of(
        grant("POCKETHIVE", "VIEW", "PH_DEPLOYMENT", "*"),
        grant("POCKETHIVE", "ALL", "PH_FOLDER", "e2e")));
  }

  public void provisionBundlesFolderAdmin() {
    adminAuthServiceClient.upsertUser(BUNDLES_FOLDER_ADMIN_ID, "local-bundles-folder-admin", "Local Bundles Folder Admin", true);
    adminAuthServiceClient.replaceGrants(BUNDLES_FOLDER_ADMIN_ID, List.of(
        grant("POCKETHIVE", "VIEW", "PH_DEPLOYMENT", "*"),
        grant("POCKETHIVE", "ALL", "PH_FOLDER", "bundles")));
  }

  private AuthServiceClient.AuthGrant grant(String product, String permission, String resourceType, String resourceSelector) {
    return new AuthServiceClient.AuthGrant(product, permission, resourceType, resourceSelector);
  }
}
