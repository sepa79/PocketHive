package io.pockethive.processor.transport;

import java.util.Map;

public record TcpRequest(String host, int port, byte[] payload, Map<String, Object> options) {}