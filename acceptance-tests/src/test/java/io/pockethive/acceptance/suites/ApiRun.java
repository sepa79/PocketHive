package io.pockethive.acceptance.suites;

import io.pockethive.acceptance.api.AuthApi;
import io.pockethive.acceptance.api.PocketHiveHttp;
import io.pockethive.acceptance.config.ApiTarget;
import io.pockethive.acceptance.evidence.RunEvidence;
import java.io.IOException;

/**
 * Responsibility: own one test's authenticated HTTP and evidence lifetime.
 * Must not: resolve targets, require fixtures or implement lifecycle/capture.
 * Contract: RESP-ACCEPTANCE-API-RUN — docs/architecture/acceptance-tests.md#resp-acceptance-api-run.
 */
final class ApiRun implements AutoCloseable {
  final PocketHiveHttp http;
  final String token;
  final RunEvidence evidence;

  private ApiRun(PocketHiveHttp http, String token, RunEvidence evidence) {
    this.http = http;
    this.token = token;
    this.evidence = evidence;
  }

  static ApiRun open(ApiTarget target, String testName) throws Exception {
    var evidence = new RunEvidence(target.evidenceDirectory(), testName);
    PocketHiveHttp http;
    try {
      http = new PocketHiveHttp(target.ingress(), target.requestTimeout());
    } catch (RuntimeException | Error failure) {
      try (evidence) { throw failure; }
    }
    try {
      String token = new AuthApi(http).devLogin(target.username());
      System.out.println("Acceptance evidence: " + evidence.directory());
      return new ApiRun(http, token, evidence);
    } catch (Exception | Error failure) {
      try (http; evidence) { throw failure; }
    }
  }

  @Override public void close() throws IOException {
    try (http; evidence) { /* Both close even when evidence reports write failures. */ }
  }
}
