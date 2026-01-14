package io.pockethive.tcpmock.service;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class TcpClientService {

    public Map<String, Object> sendViaSocket(String host, int port, String message, String delimiter, 
                                               int timeoutMs, boolean ssl, boolean sslVerify, String encoding) throws Exception {
        long startTime = System.nanoTime();
        long connectTime = 0;
        long firstByteTime = 0;
        
        Socket socket = null;
        try {
            // Create socket
            long connectStart = System.nanoTime();
            if (ssl) {
                SSLContext sslContext = createSSLContext(sslVerify);
                socket = sslContext.getSocketFactory().createSocket(host, port);
                ((SSLSocket) socket).startHandshake();
            } else {
                socket = new Socket(host, port);
            }
            socket.setSoTimeout(timeoutMs);
            connectTime = (System.nanoTime() - connectStart) / 1_000_000;
            
            // Send message
            OutputStream out = socket.getOutputStream();
            byte[] messageBytes = encodeMessage(message, encoding);
            out.write(messageBytes);
            if (delimiter != null && !delimiter.isEmpty()) {
                out.write(parseDelimiter(delimiter).getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
            
            // Read response
            long readStart = System.nanoTime();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            boolean firstByte = true;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                if (firstByte) {
                    firstByteTime = (System.nanoTime() - readStart) / 1_000_000;
                    firstByte = false;
                }
                response.write(buffer, 0, bytesRead);
                if (delimiter != null && response.toString().endsWith(parseDelimiter(delimiter))) {
                    break;
                }
            }
            
            long totalTime = (System.nanoTime() - startTime) / 1_000_000;
            
            return Map.of(
                "response", decodeMessage(response.toByteArray(), encoding),
                "connectTime", connectTime,
                "firstByteTime", firstByteTime,
                "totalTime", totalTime,
                "bytesReceived", response.size()
            );
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    public Map<String, Object> sendViaNio(String host, int port, String message, String delimiter, 
                                           int timeoutMs, boolean ssl, String encoding) throws Exception {
        long startTime = System.nanoTime();
        long connectTime = 0;
        
        java.nio.channels.SocketChannel channel = null;
        try {
            long connectStart = System.nanoTime();
            channel = java.nio.channels.SocketChannel.open();
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(timeoutMs);
            channel.connect(new java.net.InetSocketAddress(host, port));
            connectTime = (System.nanoTime() - connectStart) / 1_000_000;
            
            // Send
            byte[] messageBytes = encodeMessage(message, encoding);
            String fullMessage = new String(messageBytes, StandardCharsets.UTF_8) + 
                                (delimiter != null ? parseDelimiter(delimiter) : "");
            ByteBuffer buffer = ByteBuffer.wrap(fullMessage.getBytes(StandardCharsets.UTF_8));
            channel.write(buffer);
            
            // Read
            long readStart = System.nanoTime();
            ByteBuffer readBuffer = ByteBuffer.allocate(8192);
            int bytesRead = channel.read(readBuffer);
            long firstByteTime = (System.nanoTime() - readStart) / 1_000_000;
            readBuffer.flip();
            
            byte[] responseBytes = new byte[readBuffer.remaining()];
            readBuffer.get(responseBytes);
            
            long totalTime = (System.nanoTime() - startTime) / 1_000_000;
            
            return Map.of(
                "response", decodeMessage(responseBytes, encoding),
                "connectTime", connectTime,
                "firstByteTime", firstByteTime,
                "totalTime", totalTime,
                "bytesReceived", bytesRead
            );
        } finally {
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) {}
            }
        }
    }

    public Map<String, Object> sendViaNetty(String host, int port, String message, String delimiter, 
                                             int timeoutMs, boolean ssl, boolean sslVerify, String encoding) throws Exception {
        long startTime = System.nanoTime();
        EventLoopGroup group = new NioEventLoopGroup();
        
        try {
            CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();
            long[] connectTime = {0};
            long[] firstByteTime = {0};
            
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        if (ssl) {
                            SslContext sslContext = SslContextBuilder.forClient()
                                .trustManager(sslVerify ? null : InsecureTrustManagerFactory.INSTANCE)
                                .build();
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                        }
                        
                        ch.pipeline().addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            private boolean firstByte = true;
                            private long readStart = 0;
                            private ByteArrayOutputStream response = new ByteArrayOutputStream();
                            
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                connectTime[0] = (System.nanoTime() - startTime) / 1_000_000;
                                readStart = System.nanoTime();
                            }
                            
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                if (firstByte) {
                                    firstByteTime[0] = (System.nanoTime() - readStart) / 1_000_000;
                                    firstByte = false;
                                }
                                
                                byte[] bytes = new byte[msg.readableBytes()];
                                msg.readBytes(bytes);
                                try {
                                    response.write(bytes);
                                } catch (IOException e) {
                                    responseFuture.completeExceptionally(e);
                                    return;
                                }
                                
                                try {
                                    long totalTime = (System.nanoTime() - startTime) / 1_000_000;
                                    responseFuture.complete(Map.of(
                                        "response", decodeMessage(response.toByteArray(), encoding),
                                        "connectTime", connectTime[0],
                                        "firstByteTime", firstByteTime[0],
                                        "totalTime", totalTime,
                                        "bytesReceived", response.size()
                                    ));
                                } catch (Exception e) {
                                    responseFuture.completeExceptionally(e);
                                }
                                ctx.close();
                            }

                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                responseFuture.completeExceptionally(cause);
                                ctx.close();
                            }
                        });
                    }
                });

            Channel channel = bootstrap.connect(host, port).sync().channel();
            
            byte[] messageBytes = encodeMessage(message, encoding);
            String fullMessage = new String(messageBytes, StandardCharsets.UTF_8) + 
                                (delimiter != null ? parseDelimiter(delimiter) : "");
            ByteBuf buf = Unpooled.copiedBuffer(fullMessage, StandardCharsets.UTF_8);
            channel.writeAndFlush(buf);
            
            return responseFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            group.shutdownGracefully();
        }
    }

    private SSLContext createSSLContext(boolean verify) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        if (!verify) {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } else {
            sslContext.init(null, null, null);
        }
        return sslContext;
    }

    private byte[] encodeMessage(String message, String encoding) {
        if ("hex".equals(encoding)) {
            return hexToBytes(message);
        } else if ("base64".equals(encoding)) {
            return Base64.getDecoder().decode(message);
        } else {
            return message.getBytes(StandardCharsets.UTF_8);
        }
    }

    private String decodeMessage(byte[] bytes, String encoding) {
        if ("hex".equals(encoding)) {
            return bytesToHex(bytes);
        } else if ("base64".equals(encoding)) {
            return Base64.getEncoder().encodeToString(bytes);
        } else {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("\\s+", "");
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private String parseDelimiter(String delimiter) {
        if (delimiter == null) return "";
        return delimiter
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }
}
