package io.pockethive.acceptance.api;

import io.pockethive.auth.contract.DevLoginRequestDto;
import io.pockethive.auth.contract.SessionResponseDto;
import java.io.IOException;

/**
 * Responsibility: authenticate an explicitly selected local dev actor.
 * Must not: choose fallback credentials or log session tokens.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class AuthApi {
  private final PocketHiveHttp http;
  public AuthApi(PocketHiveHttp http) { this.http = http; }
  public String devLogin(String username) throws IOException, InterruptedException {
    return http.decode(http.request("POST", ApiSurface.AUTH.publicPath("/api/auth/dev/login"), new DevLoginRequestDto(username), "")
        .expect(200), SessionResponseDto.class).accessToken();
  }
}
