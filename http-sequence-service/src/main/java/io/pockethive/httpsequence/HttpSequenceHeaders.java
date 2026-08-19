package io.pockethive.httpsequence;

final class HttpSequenceHeaders {

  static final String STEP_INDEX = "x-ph-http-seq-step-index";
  static final String STEP_ID = "x-ph-http-seq-step-id";
  static final String SERVICE_ID = "x-ph-http-seq-service-id";
  static final String CALL_ID = "x-ph-http-seq-call-id";
  static final String STATUS = "x-ph-http-seq-status";
  static final String DURATION_MS = "x-ph-http-seq-duration-ms";
  static final String ATTEMPTS = "x-ph-http-seq-attempts";
  static final String SHA256 = "x-ph-http-seq-sha256";
  static final String RESPONSE_BYTES = "x-ph-http-seq-response-bytes";
  static final String BODY_PREVIEW = "x-ph-http-seq-body-preview";
  static final String DEBUG_REF = "x-ph-http-seq-debug-ref";
  static final String ERROR = "x-ph-http-seq-error";
  static final String FAILURE = "x-ph-http-seq-failure";
  static final String TARGET_SOURCE = "x-ph-http-seq-target-source";
  static final String SUT_ENDPOINT_ID = "x-ph-http-seq-sut-endpoint-id";

  private HttpSequenceHeaders() {
  }
}
