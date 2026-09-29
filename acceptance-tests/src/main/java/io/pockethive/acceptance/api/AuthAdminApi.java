package io.pockethive.acceptance.api;

import io.pockethive.auth.contract.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: map public Auth Service user administration using canonical DTOs.
 * Must not: infer grants, manage fixture lifetime or access the user store directly.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#provisioned-authorization-acceptance-au-9au-10au-11.
 */
public final class AuthAdminApi {
  private static final String PATH = ApiSurface.AUTH.publicPath("/api/auth/admin/users");
  private final PocketHiveHttp http;
  private final String token;
  public AuthAdminApi(PocketHiveHttp http, String token) { this.http = http; this.token = token; }
  public List<AuthenticatedUserDto> users() throws IOException, InterruptedException {
    return Arrays.asList(http.decode(http.request("GET", PATH, null, token).expect(200), AuthenticatedUserDto[].class));
  }
  public AuthenticatedUserDto upsert(UUID id, UserUpsertRequestDto request) throws IOException, InterruptedException {
    return http.decode(http.request("PUT", PATH + "/" + id, request, token).expect(200), AuthenticatedUserDto.class);
  }
  public AuthenticatedUserDto grants(UUID id, List<AuthGrantDto> grants) throws IOException, InterruptedException {
    return http.decode(http.request("PUT", PATH + "/" + id + "/grants", new UserGrantsReplaceRequestDto(grants), token)
        .expect(200), AuthenticatedUserDto.class);
  }
}
