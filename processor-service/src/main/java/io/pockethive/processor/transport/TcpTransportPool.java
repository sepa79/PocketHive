package io.pockethive.processor.transport;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TcpTransportPool {
    private static final TcpTransportPool GLOBAL_INSTANCE = new TcpTransportPool();
    private final Map<String, TcpTransport> transports = new ConcurrentHashMap<>();
    
    public static TcpTransportPool global() {
        return GLOBAL_INSTANCE;
    }
    
    public synchronized TcpTransport getOrCreate(String type) {
        return transports.computeIfAbsent(type, TcpTransportFactory::create);
    }
    
    public void close() {
        transports.values().forEach(TcpTransport::close);
        transports.clear();
    }
}