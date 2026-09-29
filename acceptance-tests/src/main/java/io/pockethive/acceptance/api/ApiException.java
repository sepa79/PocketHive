package io.pockethive.acceptance.api;

/**
 * Responsibility: report an unexpected HTTP status while retaining the response.
 * Must not: interpret domain state or print credentials.
 * Contract: RESP-ACCEPTANCE-HTTP — docs/architecture/acceptance-tests.md#resp-acceptance-http.
 */
public final class ApiException extends RuntimeException {
  private final ApiResponse response;
  public ApiException(ApiResponse response, int expected) {
    super(response + ", expected HTTP " + expected);
    this.response = response;
  }
  public ApiResponse response() { return response; }
}
