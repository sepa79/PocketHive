package io.pockethive.processor.mip;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.handler.Iso8583Endpoint;
import io.pockethive.processor.handler.Iso8583WireProfile;

/**
 * Responsibility: compose and retain the configured MIP execution resources until retirement.
 * Must not: own accepted worker configuration, parse envelopes or terminalize pending calls.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public final class MipServerRuntime implements AutoCloseable {
  private final MipServerConfigResolver resolver;
  private final MipListenerFactory listenerFactory;
  private final Iso8583Codec codec = new Iso8583Codec();
  private MipRuntimeState state = MipRuntimeState.UNCONFIGURED;
  private MipServerConfig activeConfig;
  private MipSession session;
  private MipNetworkManagement network;
  private MipListener listener;

  public MipServerRuntime(ObjectMapper mapper) { this(mapper, NettyMipServer::new); }

  public MipServerRuntime(ObjectMapper mapper, MipListenerFactory listenerFactory) {
    this.resolver = new MipServerConfigResolver(mapper);
    this.listenerFactory = java.util.Objects.requireNonNull(listenerFactory, "listenerFactory");
  }

  public synchronized void configure(ProcessorWorkerConfig worker) {
    if (state == MipRuntimeState.RETIRED) return;
    try {
      boolean mip = Iso8583Endpoint.selectsMipServer(worker.baseUrl());
      if (!mip) {
        if (state == MipRuntimeState.ACTIVE) close();
        else state = MipRuntimeState.CLIENT;
        return;
      }
      MipServerConfig desired = resolver.resolve(worker);
      if (state == MipRuntimeState.ACTIVE) {
        if (!activeConfig.equals(desired)) close();
        return;
      }
      if (state == MipRuntimeState.CLIENT) {
        close();
        throw new IllegalStateException("Changing to the MIP adapter requires worker restart");
      }
      for (MipMessageType type : MipMessageType.values()) {
        codec.validateFields(desired.settings().schemaRef(), type.value(), type.requiredFields());
      }
      MipSession selectedSession = new MipSession(desired.settings().maxPending());
      MipNetworkManagement selectedNetwork = new MipNetworkManagement(codec, desired.settings().schemaRef(),
          desired.settings().networkManagement());
      var dispatcher = new MipMessageDispatcher(codec, desired.settings().schemaRef(), selectedSession, selectedNetwork);
      var pipeline = new MipChannelInitializer(selectedSession, dispatcher,
          Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP);
      MipListener selectedListener;
      try { selectedListener = listenerFactory.bind(desired, pipeline); }
      catch (Exception failure) { selectedSession.close(); throw failure; }
      activeConfig = desired;
      session = selectedSession;
      network = selectedNetwork;
      listener = selectedListener;
      state = MipRuntimeState.ACTIVE;
    } catch (Exception failure) {
      close();
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("Cannot initialize MIP server; worker restart required", failure);
    }
  }

  public MipReply exchange(byte[] payload, ProcessorWorkerConfig worker) throws Exception {
    MipSession selectedSession;
    MipNetworkManagement selectedNetwork;
    MipServerConfig selectedConfig;
    synchronized (this) {
      if (state != MipRuntimeState.ACTIVE) throw new IllegalStateException("MIP server is not active; worker restart required");
      selectedConfig = resolver.resolve(worker);
      if (!activeConfig.equals(selectedConfig)) {
        close();
        throw new IllegalStateException("Changed MIP server settings require worker restart");
      }
      selectedSession = session;
      selectedNetwork = network;
    }
    var decoded = codec.decode(payload, selectedConfig.settings().schemaRef());
    MipMessageType type = MipMessageType.fromValue(decoded.mti());
    if (type == MipMessageType.NETWORK_REQUEST) selectedNetwork.validateRequest(decoded);
    return selectedSession.exchange(payload, decoded, worker.timeoutMs());
  }

  public synchronized void configurationRemoved() {
    if (state != MipRuntimeState.UNCONFIGURED) close();
  }

  @Override public synchronized void close() {
    state = MipRuntimeState.RETIRED;
    if (session != null) session.close();
    if (listener != null) listener.close();
    session = null;
    listener = null;
  }
}
