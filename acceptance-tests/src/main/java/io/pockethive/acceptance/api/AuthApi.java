package io.pockethive.acceptance.api;

import io.pockethive.auth.contract.DevLoginRequestDto;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.SessionResponseDto;
import java.io.IOException;

/**
 * Responsibility: authenticate an explicit local dev actor and read its canonical profile.
 * Must not: choose fallback credentials or log session tokens.
 * Contract: RESP-ACCEPTANCE-API — docs/architecture/acceptance-tests.md#resp-acceptance-api.
 */
public final class AuthApi {
  private final PocketHiveHttp http;
  public AuthApi(PocketHiveHttp http) { this.http = http; }
  public AuthenticatedUserDto profile(String token) throws IOException, InterruptedException {
    return http.decode(http.request("GET", ApiSurface.AUTH.publicPath("/api/auth/me"), null, token)
        .expect(200), AuthenticatedUserDto.class);
  }
  public String devLogin(String username) throws IOException, InterruptedException {
    return http.decode(http.request("POST", ApiSurface.AUTH.publicPath("/api/auth/dev/login"), new DevLoginRequestDto(username), "")
        .expect(200), SessionResponseDto.class).accessToken();
  }
}
