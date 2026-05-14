package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SocketTransport implements TcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(SocketTransport.class);
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
        int connectTimeout = (Integer) request.options().getOrDefault("connectTimeoutMs", config != null ? config.connectTimeoutMs() : 5000);
        int readTimeout = (Integer) request.options().getOrDefault("readTimeoutMs", config != null ? config.readTimeoutMs() : 30000);

        if (shouldPool(request)) {
            return executeWithPooledSocket(request, behavior, start, connectTimeout, readTimeout, useSsl);
        }

        boolean keepAlive = config == null || config.keepAlive();
        boolean tcpNoDelay = config == null || config.tcpNoDelay();
        try (Socket socket = useSsl ? createSslSocket(request, sslVerify, connectTimeout)
                                    : new Socket()) {
            if (!useSsl) {
                socket.connect(new java.net.InetSocketAddress(request.host(), request.port()), connectTimeout);
            }
            return executeWithSocket(socket, request, behavior, start, readTimeout, keepAlive, tcpNoDelay);
        } catch (Exception e) {
            throw new TcpException("TCP operation failed", e);
        }
    }

    private boolean shouldPool(TcpRequest request) {
        if (config == null) {
            return false;
        }
        if (request.options().containsKey("keyStorePath")) {
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
                                               int connectTimeout,
                                               int readTimeout,
                                               boolean useSsl) throws TcpException {
        Socket socket = null;
        try {
            socket = pool.getOrCreate(
                request.host(),
                request.port(),
                useSsl,
                connectTimeout,
                config.keepAlive(),
                config.tcpNoDelay()
            );
            TcpResponse response = executeWithSocket(socket, request, behavior, start, readTimeout, config.keepAlive(), config.tcpNoDelay());
            pool.returnToPool(request.host(), request.port(), useSsl, socket);
            return response;
        } catch (Exception ex) {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
            pool.remove(request.host(), request.port(), useSsl);
            throw new TcpException("TCP operation failed", ex);
        }
    }

    private TcpResponse executeWithSocket(Socket socket,
                                         TcpRequest request,
                                         TcpBehavior behavior,
                                         long start,
                                         int readTimeout,
                                         boolean keepAlive,
                                         boolean tcpNoDelay) throws IOException {
        socket.setSoTimeout(readTimeout);
        socket.setKeepAlive(keepAlive);
        socket.setTcpNoDelay(tcpNoDelay);

        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        logger.debug("TCP_SEND host={} port={} bytes={} payload=<redacted>",
            request.host(), request.port(), request.payload().length);

        out.write(request.payload());
        out.flush();

        if (behavior == TcpBehavior.FIRE_FORGET) {
            return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
        }

        byte[] response = readResponse(in, request, behavior);
        long latency = System.currentTimeMillis() - start;

        logger.debug("TCP_RECV host={} port={} bytes={} latency={}ms payload=<redacted>",
            request.host(), request.port(), response.length, latency);

        return new TcpResponse(200, response, latency);
    }

    private byte[] readResponse(InputStream in, TcpRequest request, TcpBehavior behavior) throws IOException {
        ResponseReader reader = ResponseReader.forBehavior(behavior);
        return reader.read(in, request);
    }

    private Socket createSslSocket(TcpRequest request, boolean verify, int connectTimeout) throws Exception {
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        KeyManager[] keyManagers = keyManagers(request);
        if (!verify) {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            sslContext.init(keyManagers, trustAllCerts, new java.security.SecureRandom());
        } else {
            sslContext.init(keyManagers, null, null);
        }

        javax.net.ssl.SSLSocketFactory factory = sslContext.getSocketFactory();
        javax.net.ssl.SSLSocket sslSocket = (javax.net.ssl.SSLSocket) factory.createSocket();
        sslSocket.connect(new java.net.InetSocketAddress(request.host(), request.port()), connectTimeout);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static KeyManager[] keyManagers(TcpRequest request) throws Exception {
        Object keyStorePath = request.options().get("keyStorePath");
        if (keyStorePath == null || keyStorePath.toString().isBlank()) {
            return null;
        }
        String type = request.options().getOrDefault("keyStoreType", "PKCS12").toString();
        char[] password = request.options().getOrDefault("keyStorePassword", "").toString().toCharArray();
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Path.of(keyStorePath.toString()))) {
            keyStore.load(in, password);
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        return factory.getKeyManagers();
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.closeAll();
        }
    }
}
