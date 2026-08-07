package io.pockethive.functionalswarmdsl;

import io.pockethive.requestexecution.ApacheHttpRequestExecutor;
import io.pockethive.requestexecution.RequestExecutor;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;

/** Factory for explicit local and remote Functional Swarm invokers. */
public final class FunctionalSwarmDsl {
  private FunctionalSwarmDsl() {
  }

  public static FunctionalSwarmInvoker local(FunctionalSwarmLocalConfig config, HttpClient httpClient) {
    return local(config, new ApacheHttpRequestExecutor(Objects.requireNonNull(httpClient, "httpClient")));
  }

  public static FunctionalSwarmInvoker local(FunctionalSwarmLocalConfig config, RequestExecutor requestExecutor) {
    return new LocalFunctionalSwarmInvoker(config, requestExecutor);
  }

  public static FunctionalSwarmInvoker remote(FunctionalSwarmRemoteConfig config) {
    return new RedisFunctionalSwarmInvoker(config);
  }
}
