package io.pockethive.processor.transport;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class NioTransport implements TcpTransport {
    
    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();
        
        try (SocketChannel channel = SocketChannel.open()) {
            int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
            channel.socket().setSoTimeout(timeout);
            channel.connect(new InetSocketAddress(request.host(), request.port()));
            
            ByteBuffer writeBuffer = ByteBuffer.wrap(request.payload());
            channel.write(writeBuffer);
            
            if (behavior == TcpBehavior.FIRE_FORGET) {
                return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
            }
            
            byte[] response = readResponse(channel, request, behavior);
            return new TcpResponse(200, response, System.currentTimeMillis() - start);
            
        } catch (Exception e) {
            throw new TcpException("NIO operation failed", e);
        }
    }
    
    private byte[] readResponse(SocketChannel channel, TcpRequest request, TcpBehavior behavior) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer readBuffer = ByteBuffer.allocate(1024);
        
        if (behavior == TcpBehavior.ECHO) {
            int totalRead = 0;
            while (totalRead < request.payload().length) {
                int read = channel.read(readBuffer);
                if (read == -1) break;
                readBuffer.flip();
                baos.write(readBuffer.array(), 0, read);
                totalRead += read;
                readBuffer.clear();
            }
        } else if (behavior == TcpBehavior.STREAMING) {
            int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);
            int totalRead = 0;
            
            while (totalRead < maxBytes) {
                int read = channel.read(readBuffer);
                if (read == -1) break;
                readBuffer.flip();
                baos.write(readBuffer.array(), 0, read);
                totalRead += read;
                readBuffer.clear();
            }
        } else {
            String endTag = (String) request.options().getOrDefault("endTag", "</Document>");
            StringBuilder response = new StringBuilder();
            
            while (true) {
                int read = channel.read(readBuffer);
                if (read == -1) break;
                readBuffer.flip();
                response.append(StandardCharsets.UTF_8.decode(readBuffer));
                readBuffer.clear();
                if (response.toString().contains(endTag)) break;
            }
            return response.toString().getBytes(StandardCharsets.UTF_8);
        }
        
        return baos.toByteArray();
    }
    
    @Override
    public void close() {
        // No persistent resources to close
    }
}