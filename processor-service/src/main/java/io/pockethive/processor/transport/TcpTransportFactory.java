package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;

/**
 * Responsibility: construct the configured processor Socket/NIO/Netty transport.
 * Must not: own pools, reload state, retry requests or interpret protocol results.
 * Contract: RESP-PROCESSOR-TCP-RUNTIME — docs/architecture/runtime-responsibilities.md#resp-processor-tcp-runtime.
 */
final class TcpTransportFactory {
    static TcpTransport create(TcpTransportConfig config) {
        if (config == null || config.type() == null) {
            return new SocketTransport();
        }
        return switch (config.type().toLowerCase()) {
            case "socket" -> new SocketTransport(config);
            case "nio" -> new NioTransport(config);
            case "netty" -> new NettyTransport(config);
            default -> new SocketTransport(config);
        };
    }
    
}
