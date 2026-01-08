package io.pockethive.processor.transport;

import io.pockethive.processor.TcpTransportConfig;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NioTransport implements TcpTransport {

    private static final Logger logger = LoggerFactory.getLogger(NioTransport.class);
    private final TcpTransportConfig config;

    public NioTransport() {
        this.config = null;
    }

    public NioTransport(TcpTransportConfig config) {
        this.config = config;
    }

    @Override
    public TcpResponse execute(TcpRequest request, TcpBehavior behavior) throws TcpException {
        long start = System.currentTimeMillis();

        try (SocketChannel channel = SocketChannel.open()) {
            int connectTimeoutMs = (Integer) request.options().getOrDefault(
                "connectTimeoutMs",
                config != null ? config.connectTimeoutMs() : TcpTransportConfig.defaults().connectTimeoutMs()
            );
            int readTimeoutMs = (Integer) request.options().getOrDefault(
                "readTimeoutMs",
                config != null ? config.readTimeoutMs() : TcpTransportConfig.defaults().readTimeoutMs()
            );
            channel.socket().connect(new InetSocketAddress(request.host(), request.port()), connectTimeoutMs);
            channel.socket().setSoTimeout(readTimeoutMs);

            if (logger.isDebugEnabled()) {
                logger.debug("TCP_SEND host={} port={} bytes={} payload={}",
                    request.host(), request.port(), request.payload().length,
                    new String(request.payload(), StandardCharsets.UTF_8));
            }

            ByteBuffer writeBuffer = ByteBuffer.wrap(request.payload());
            while (writeBuffer.hasRemaining()) {
                channel.write(writeBuffer);
            }

            if (behavior == TcpBehavior.FIRE_FORGET) {
                return new TcpResponse(200, new byte[0], System.currentTimeMillis() - start);
            }

            InputStream in = channel.socket().getInputStream();
            byte[] response = ResponseReader.forBehavior(behavior).read(in, request);
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

    @Override
    public void close() {
        // No persistent resources to close
    }
}
