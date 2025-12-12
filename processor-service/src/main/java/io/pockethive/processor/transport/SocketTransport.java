package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public class SocketTransport implements TcpTransport {

    private final TcpTransportConfig config;
    private final TcpConnectionPool pool;
    private final ConcurrentHashMap<String, Object> locks;

    public SocketTransport() {
        this.config = null;
        this.pool = null;
        this.locks = null;
    }

    public SocketTransport(TcpTransportConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.pool = new TcpConnectionPool(config.sslVerify());
        this.locks = new ConcurrentHashMap<>();
    }

    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();
        
        boolean useSsl = Boolean.TRUE.equals(request.options().get("ssl"));
        boolean sslVerify = Boolean.TRUE.equals(request.options().getOrDefault("sslVerify", false));
        int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
        
        if (shouldPool()) {
            return executeWithPooledSocket(request, behavior, start, timeout, useSsl);
        }

        boolean keepAlive = config == null || config.keepAlive();
        boolean tcpNoDelay = config == null || config.tcpNoDelay();
        try (Socket socket = useSsl ? createSslSocket(request.host(), request.port(), sslVerify)
                                    : new Socket(request.host(), request.port())) {
            return executeWithSocket(socket, request, behavior, start, timeout, keepAlive, tcpNoDelay);
        } catch (Exception e) {
            throw new TcpException("TCP operation failed", e);
        }
    }

    private boolean shouldPool() {
        if (config == null) {
            return false;
        }
        if (!config.keepAlive()) {
            return false;
        }
        return config.connectionReuse() != TcpTransportConfig.ConnectionReuse.NONE;
    }

    private TcpResponse executeWithPooledSocket(TcpRequest request,
                                               TcpBehavior behavior,
                                               long start,
                                               int timeout,
                                               boolean useSsl) throws TcpException {
        String key = (useSsl ? "tcps://" : "tcp://") + request.host() + ":" + request.port();
        Object lock = locks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            Socket socket = null;
            try {
                socket = pool.getOrCreate(
                    request.host(),
                    request.port(),
                    useSsl,
                    timeout,
                    config.keepAlive(),
                    config.tcpNoDelay()
                );
                return executeWithSocket(socket, request, behavior, start, timeout, config.keepAlive(), config.tcpNoDelay());
            } catch (Exception ex) {
                pool.remove(request.host(), request.port(), useSsl);
                throw new TcpException("TCP operation failed", ex);
            }
        }
    }

    private TcpResponse executeWithSocket(Socket socket,
                                         TcpRequest request,
                                         TcpBehavior behavior,
                                         long start,
                                         int timeout,
                                         boolean keepAlive,
                                         boolean tcpNoDelay) throws IOException {
        socket.setSoTimeout(timeout);
        socket.setKeepAlive(keepAlive);
        socket.setTcpNoDelay(tcpNoDelay);

        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        out.write(request.payload());
        out.flush();

        if (behavior == TcpBehavior.FIRE_FORGET) {
            return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
        }

        byte[] response = readResponse(in, request, behavior);
        return new TcpResponse(200, response, System.currentTimeMillis() - start);
    }
    
    private byte[] readResponse(InputStream in, TcpRequest request, TcpBehavior behavior) throws IOException {
        if (behavior == TcpBehavior.ECHO) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
                if (baos.size() >= request.payload().length) break;
            }
            return baos.toByteArray();
        }
        
        if (behavior == TcpBehavior.STREAMING) {
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int totalRead = 0;
            int read;
            while (totalRead < maxBytes && (read = in.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
                totalRead += read;
            }
            return baos.toByteArray();
        }
        
        // REQUEST_RESPONSE behavior - read byte-by-byte like the working example
        String endTag = (String) request.options().getOrDefault("endTag", "</Document>");
        StringBuilder response = new StringBuilder();
        int readByte;
        
        while ((readByte = in.read()) != -1) {
            response.append((char) readByte);
            if (readByte == '>' && response.lastIndexOf(endTag) != -1) {
                break;
            }
        }
        
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }
    
    private Socket createSslSocket(String host, int port, boolean verify) throws Exception {
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        if (!verify) {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } else {
            sslContext.init(null, null, null);
        }
        
        javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) sslContext.getSocketFactory().createSocket(host, port);
        sslSocket.startHandshake();
        return sslSocket;
    }
    
    @Override
    public void close() {
        if (pool != null) {
            pool.closeAll();
        }
    }
}
