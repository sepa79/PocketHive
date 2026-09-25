package io.pockethive.tcpmock.config;

import io.pockethive.auth.client.AuthServiceClient;
import io.pockethive.auth.client.AuthServiceClientException;
import io.pockethive.auth.contract.AuthenticatedUserDto;
import io.pockethive.auth.contract.PocketHiveGrantChecks;
import io.pockethive.auth.contract.PocketHivePermissionIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Responsibility: resolve administrative bearer identity and apply canonical global grant checks.
 * Must not: authenticate TCP traffic, cache authority, persist users or mutate workspaces.
 * Contract: RESP-TCP-MOCK-AUTHENTICATION —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-authentication.
 */
public final class TcpMockAuthFilter extends OncePerRequestFilter {
  private static final Set<String> READ =
      Set.of(
          PocketHivePermissionIds.VIEW, PocketHivePermissionIds.RUN, PocketHivePermissionIds.ALL);
  private static final Set<String> WRITE = Set.of(PocketHivePermissionIds.ALL);
  private final AuthServiceClient client;
  private final RequestMatcher publicRequests;

  public TcpMockAuthFilter(AuthServiceClient client, RequestMatcher publicRequests) {
    this.client = client;
    this.publicRequests = publicRequests;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return publicRequests.matches(request);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith("Bearer ") || header.substring(7).isBlank()) {
      response.sendError(401, "PocketHive bearer authentication required");
      return;
    }
    AuthenticatedUserDto user;
    try {
      user = client.resolve(header);
    } catch (AuthServiceClientException error) {
      response.sendError(
          error.getStatusCode() == 401 ? 401 : 503, "PocketHive authentication failed");
      return;
    } catch (RuntimeException error) {
      response.sendError(503, "PocketHive authentication unavailable");
      return;
    }
    if (user == null) {
      response.sendError(503, "PocketHive authentication returned no identity");
      return;
    }
    boolean read =
        HttpMethod.GET.matches(request.getMethod()) || HttpMethod.HEAD.matches(request.getMethod());
    if (!user.active()
        || !PocketHiveGrantChecks.hasPermissionInScope(user, read ? READ : WRITE, null, null)) {
      response.sendError(403, "Global PocketHive permission required");
      return;
    }
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
