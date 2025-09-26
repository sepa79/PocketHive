package io.pockethive.controlplane.consumer;

@FunctionalInterface
public interface ControlSignalHandler {

    void handle(ControlSignalEnvelope envelope) throws Exception;
}
