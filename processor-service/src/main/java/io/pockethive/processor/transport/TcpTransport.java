package io.pockethive.processor.transport;

public interface TcpTransport {
    TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException;
    void close();
}