package io.pockethive.processor.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NioTransport implements TcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(NioTransport.class);

    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();

        try (SocketChannel channel = SocketChannel.open()) {
            int timeout = (Integer) request.options().getOrDefault("timeout", 30000);
            channel.socket().setSoTimeout(timeout);
            channel.connect(new InetSocketAddress(request.host(), request.port()));

            if (logger.isDebugEnabled()) {
                logger.debug("TCP_SEND host={} port={} bytes={} payload={}",
                    request.host(), request.port(), request.payload().length,
                    new String(request.payload(), StandardCharsets.UTF_8));
            }

            ByteBuffer writeBuffer = ByteBuffer.wrap(request.payload());
            channel.write(writeBuffer);

            if (behavior == TcpBehavior.FIRE_FORGET) {
                return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
            }

            byte[] response = readResponseDirect(channel, request, behavior);
            long latency = System.currentTimeMillis() - start;

            if (logger.isDebugEnabled()) {
                logger.debug("TCP_RECV host={} port={} bytes={} latency={}ms payload={}",
                    request.host(), request.port(), response.length, latency,
                    new String(response, StandardCharsets.UTF_8));
            }

            return new TcpResponse(200, response, latency);

        } catch (Exception e) {
            throw new TcpException("NIO operation failed", e);
        }
    }

    private byte[] readResponseDirect(SocketChannel channel, TcpRequest request, TcpBehavior behavior) throws IOException {
        String endTag = (String) request.options().get("endTag");
        int maxBytes = (Integer) request.options().getOrDefault("maxBytes", 8192);

        if (behavior == TcpBehavior.ECHO) {
            if (endTag != null) {
                return readUntilDelimiterNio(channel, endTag, false);
            }
            int expectedBytes = request.payload().length;
            ByteBuffer buffer = ByteBuffer.allocate(expectedBytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) == -1) break;
            }
            return buffer.array();
        }

        if (behavior == TcpBehavior.STREAMING) {
            ByteBuffer buffer = ByteBuffer.allocate(Math.min(maxBytes, 8192));
            int totalRead = 0;
            while (totalRead < maxBytes) {
                int read = channel.read(buffer);
                if (read == -1) break;
                totalRead += read;
            }
            buffer.flip();
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            return result;
        }

        // REQUEST_RESPONSE
        if (endTag != null) {
            return readUntilDelimiterNio(channel, endTag, false);
        }

        ByteBuffer buffer = ByteBuffer.allocate(Math.min(maxBytes, 8192));
        int totalRead = 0;
        while (totalRead < maxBytes) {
            int read = channel.read(buffer);
            if (read == -1) break;
            totalRead += read;
            if (totalRead > 0 && channel.socket().getInputStream().available() == 0) {
                break;
            }
        }
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private byte[] readUntilDelimiterNio(SocketChannel channel, String delimiter, boolean stripDelimiter) throws IOException {
        byte[] delimBytes = delimiter.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int matchPos = 0;

        while (true) {
            buffer.clear();
            int read = channel.read(buffer);
            if (read == -1) break;
            buffer.flip();

            for (int i = 0; i < read; i++) {
                byte b = buffer.get();
                baos.write(b);
                if (b == delimBytes[matchPos]) {
                    matchPos++;
                    if (matchPos == delimBytes.length) {
                        byte[] result = baos.toByteArray();
                        return stripDelimiter ? java.util.Arrays.copyOf(result, result.length - delimBytes.length) : result;
                    }
                } else {
                    matchPos = (b == delimBytes[0]) ? 1 : 0;
                }
            }
        }
        return baos.toByteArray();
    }

    @Override
    public void close() {
        // No persistent resources to close
    }
}
