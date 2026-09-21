package io.pockethive.work.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: expose the selected WorkPlane owner's bootstrap and connection environment projections.
 * Must not: select an adapter, mutate accepted configuration or create resources.
 * Contract: RESP-WORK-CONNECTION-ENVIRONMENT — docs/architecture/runtime-responsibilities.md#resp-work-connection-environment.
 */
public interface WorkAdapterEnvironment {
    Map<String, String> connectionEnvironment();
    void validateConnection(Function<String, String> properties);
    List<WorkConfigurationProblem> overrideProblems(Function<String, String> properties);
    WorkBootstrapProjection bootstrap(Map<String, Object> configuration, Map<String, String> destinationEnvironment);
}
