package io.pockethive.processor.transport;

public class TcpException extends Exception {
    public TcpException(String message) { super(message); }
    public TcpException(String message, Throwable cause) { super(message, cause); }
}