package io.pockethive.processor.transport;

public class TcpTransportFactory {
    
    public static TcpTransport create(String transportType) {
        return switch (transportType.toLowerCase()) {
            case "socket" -> new SocketTransport();
            case "nio" -> new NioTransport();
            case "netty" -> new NettyTransport();
            default -> new SocketTransport();
        };
    }
    
    public static TcpTransport createPooled(String transportType) {
        return TcpTransportPool.global().getOrCreate(transportType);
    }
}