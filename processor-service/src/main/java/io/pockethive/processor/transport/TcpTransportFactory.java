package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;

public class TcpTransportFactory {
    
    public static TcpTransport create(String transportType) {
        return switch (transportType.toLowerCase()) {
            case "socket" -> new SocketTransport();
            case "nio" -> new NioTransport();
            case "netty" -> new NettyTransport();
            default -> new SocketTransport();
        };
    }

    public static TcpTransport create(TcpTransportConfig config) {
        if (config == null || config.type() == null) {
            return new SocketTransport();
        }
        return switch (config.type().toLowerCase()) {
            case "socket" -> new SocketTransport(config);
            case "nio" -> new NioTransport();
            case "netty" -> new NettyTransport();
            default -> new SocketTransport(config);
        };
    }
    
    public static TcpTransport createPooled(String transportType) {
        return TcpTransportPool.global().getOrCreate(transportType);
    }
}
