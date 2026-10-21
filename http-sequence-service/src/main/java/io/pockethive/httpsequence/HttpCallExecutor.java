package io.pockethive.httpsequence;

import java.net.URI;

/**
 * Responsibility: execute native requests or explicitly single-attempt portable requests.
 * Must not: render templates, select targets, interpret responses or mutate journey state.
 * Contract: RESP-HTTP-SEQUENCE-HTTP-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-http-sequence-http-execution.
 */
interface HttpCallExecutor {

  HttpCallResult execute(URI target, RenderedCall call) throws Exception;

  default HttpCallResult executeOnce(URI target, RenderedCall call) throws Exception {
    throw new UnsupportedOperationException("Single-attempt portable HTTP execution is not supported");
  }
}
