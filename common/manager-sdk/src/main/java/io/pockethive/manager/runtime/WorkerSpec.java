package io.pockethive.manager.runtime;

import java.util.List;
import java.util.Map;

/**
 * Description of a worker instance that the compute adapter should reconcile.
 */
public record WorkerSpec(
    String id,
    String role,
    String image,
    Map<String, String> environment,
    List<String> volumes
) {
}

