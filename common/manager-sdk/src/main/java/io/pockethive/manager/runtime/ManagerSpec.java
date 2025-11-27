package io.pockethive.manager.runtime;

import java.util.List;
import java.util.Map;

/**
 * Description of a manager/controller process that the compute adapter should start.
 */
public record ManagerSpec(
    String id,
    String image,
    Map<String, String> environment,
    List<String> volumes
) {
}

