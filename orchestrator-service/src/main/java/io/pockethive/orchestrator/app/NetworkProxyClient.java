package io.pockethive.orchestrator.app;

import io.pockethive.swarm.model.NetworkBinding;
import io.pockethive.swarm.model.NetworkBindingClearRequest;
import io.pockethive.swarm.model.NetworkBindingRequest;

public interface NetworkProxyClient {

    NetworkBinding bindSwarm(String swarmId,
                             NetworkBindingRequest request,
                             String correlationId,
                             String idempotencyKey) throws Exception;

    NetworkBinding clearSwarm(String swarmId,
                              NetworkBindingClearRequest request,
                              String correlationId,
                              String idempotencyKey) throws Exception;
}
