package io.pockethive.httpsequence;

import java.net.URI;
import java.util.List;

interface HttpSequenceTargetResolver {

  List<BaseTarget> resolveBases(HttpSequenceWorkerConfig config);

  ResolvedTarget resolve(BaseTarget baseTarget, String renderedPath);

  enum TargetSource {
    WORKER_BASE_URL,
    SUT_ENDPOINT,
    STEP_BASE_URL
  }

  record BaseTarget(URI baseUri, TargetSource source, String sutEndpointId) {
  }

  record ResolvedTarget(URI uri, TargetSource source, String sutEndpointId) {
  }
}
