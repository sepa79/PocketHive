package io.pockethive.acceptance.api;

/**
 * Responsibility: preserve an HTTP response for caller assertions.
 * Must not: decide domain outcomes or expose response bodies in toString.
 * Contract: RESP-ACCEPTANCE-HTTP — docs/architecture/acceptance-tests.md#resp-acceptance-http.
 */
public record ApiResponse(String method, String path, int status, String body) {
  public ApiResponse expect(int expected) {
    if (status != expected) throw new ApiException(this, expected);
    return this;
  }
  @Override public String toString() { return method + " " + path + " -> HTTP " + status; }
}
