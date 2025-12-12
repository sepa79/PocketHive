package io.pockethive.processor.transport;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class SocketTransport implements TcpTransport {
    
    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();
        
        boolean useSsl = request.host().startsWith("tcps://") || 
                        Boolean.TRUE.equals(request.options().get("ssl"));
        boolean sslVerify = Boolean.TRUE.equals(request.options().getOrDefault("sslVerify", false));
        
        try (Socket socket = useSsl ? createSslSocket(request.host(), request.port(), sslVerify) 
                                    : new Socket(request.host(), request.port())) {
            int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
            socket.setSoTimeout(timeout);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            
            out.write(request.payload());
            out.flush();
            
            if (behavior == TcpBehavior.FIRE_FORGET) {
                return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
            }
            
            byte[] response = readResponse(in, request, behavior);
            return new TcpResponse(200, response, System.currentTimeMillis() - start);
            
        } catch (Exception e) {
            throw new TcpException("TCP operation failed", e);
        }
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
        // No persistent connections to close
    }
}
