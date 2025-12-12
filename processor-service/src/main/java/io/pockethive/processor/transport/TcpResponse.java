package io.pockethive.processor.transport;

public record TcpResponse(int status, byte[] body, long durationMs) {}