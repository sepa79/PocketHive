package io.pockethive.acceptance.resources;

import io.pockethive.acceptance.api.AuthAdminApi;
import io.pockethive.acceptance.api.AuthApi;
import io.pockethive.acceptance.api.ApiException;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.auth.contract.*;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: provision and revoke/deactivate one uniquely owned acceptance user.
 * Must not: change existing users, implement grant decisions or claim physical deletion.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
public final class AuthUserResource implements AutoCloseable {
  private final UUID id;
  private final String username;
  private final AuthAdminApi admin;
  private final AuthApi auth;
  private final RunEvidence evidence;
  private AcquisitionState state = AcquisitionState.NOT_REQUESTED;

  public AuthUserResource(UUID id, String username, AuthAdminApi admin, AuthApi auth, RunEvidence evidence) {
    this.id = id; this.username = username; this.admin = admin; this.auth = auth; this.evidence = evidence;
  }
  public UUID id() { return id; }
  public String username() { return username; }

  public void provision(List<AuthGrantDto> grants) throws Exception {
    if (state != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("User creation already attempted");
    if (admin.users().stream().anyMatch(user -> id.equals(user.id()) || username.equals(user.username()))) {
      throw new IllegalStateException("Acceptance user identity already exists: " + id);
    }
    state = AcquisitionState.UNCONFIRMED;
    requireIdentity(admin.upsert(id, new UserUpsertRequestDto(username, username, true)));
    state = AcquisitionState.ACQUIRED;
    var configured = admin.grants(id, grants);
    requireIdentity(configured);
    if (!configured.active() || !configured.grants().equals(grants)) {
      throw new IllegalStateException("User/grant provisioning not confirmed: " + id);
    }
    evidence.record("user-" + id + "-provisioned", configured);
  }

  @Override public void close() throws Exception {
    if (state == AcquisitionState.NOT_REQUESTED || state == AcquisitionState.RELEASED) return;
    var owned = admin.users().stream().filter(user -> id.equals(user.id())).toList();
    if (owned.isEmpty() && state == AcquisitionState.UNCONFIRMED) {
      evidence.record("user-" + id + "-unconfirmed", id);
      throw new IllegalStateException("Unconfirmed user creation: " + id
          + "; absent readback cannot confirm cleanup after an unknown dispatch");
    }
    if (owned.size() != 1) throw new IllegalStateException("Cannot identify owned user: " + id);
    requireIdentity(owned.getFirst());
    Exception failure = null;
    try { requireIdentity(admin.grants(id, List.of())); }
    catch (Exception problem) { failure = problem; }
    // Deactivation still runs if revocation fails; neither error is hidden.
    try { requireIdentity(admin.upsert(id, new UserUpsertRequestDto(username, username, false))); }
    catch (Exception problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
    try {
      var remaining = admin.users().stream().filter(user -> id.equals(user.id())).toList();
      if (remaining.size() != 1) throw new IllegalStateException("Missing cleanup readback: " + id);
      var user = remaining.getFirst();
      requireIdentity(user);
      if (user.active() || !user.grants().isEmpty()) throw new IllegalStateException("User cleanup incomplete: " + id);
      requireLoginDenied();
      state = AcquisitionState.RELEASED;
      evidence.record("user-" + id + "-deactivated", user);
    } catch (Exception problem) { if (failure == null) failure = problem; else failure.addSuppressed(problem); }
    if (failure != null) throw failure;
  }

  private void requireLoginDenied() throws Exception {
    try { auth.devLogin(username); }
    catch (ApiException denied) {
      denied.response().expect(401);
      evidence.record("user-" + id + "-login-denied", denied.response());
      return;
    }
    throw new IllegalStateException("Inactive user can still log in: " + id);
  }
  private void requireIdentity(AuthenticatedUserDto user) {
    if (!id.equals(user.id()) || !username.equals(user.username())) {
      throw new IllegalStateException("User response identity mismatch: " + id);
    }
  }
}
