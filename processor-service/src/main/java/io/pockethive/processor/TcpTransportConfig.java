package io.pockethive.processor;

public record TcpTransportConfig(
    String type,           // socket, nio, netty
    int connectTimeoutMs,  // connection timeout ms
    int readTimeoutMs,     // read/SO_TIMEOUT ms
    int maxBytes,          // max bytes for streaming
    boolean keepAlive,     // socket keep alive
    int workerThreads,     // netty worker threads
    boolean tcpNoDelay,    // TCP_NODELAY option
    boolean sslVerify,     // SSL certificate verification
    ConnectionReuse connectionReuse,  // connection pooling strategy
    int maxRetries         // retry attempts
) {

    public enum ConnectionReuse {
        GLOBAL, PER_THREAD, NONE
    }

    public static TcpTransportConfig defaults() {
        return new TcpTransportConfig(
            "socket",
            5000,
            30000,
            8192,
            true,
            4,
            true,
            false,
            ConnectionReuse.GLOBAL,
            2
        );
    }
}
