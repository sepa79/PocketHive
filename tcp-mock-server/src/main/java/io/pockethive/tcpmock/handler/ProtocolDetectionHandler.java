package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.config.TcpMockConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.util.List;

public class ProtocolDetectionHandler extends ByteToMessageDecoder {
    private final TcpMockConfig config;
    private boolean protocolDetected = false;

    public ProtocolDetectionHandler(TcpMockConfig config) {
        this.config = config;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (protocolDetected) {
            out.add(in.readBytes(in.readableBytes()));
            return;
        }

        if (in.readableBytes() < 4) return;

        // Detect protocol type
        String protocol = detectProtocol(in);
        setupPipeline(ctx, protocol);
        protocolDetected = true;

        // Forward remaining data
        out.add(in.readBytes(in.readableBytes()));
    }

    private String detectProtocol(ByteBuf buffer) {
        int readerIndex = buffer.readerIndex();

        try {
            // Check for length-prefixed (binary)
            if (buffer.readableBytes() >= 4) {
                int length = buffer.readInt();
                if (length > 0 && length < 65536) {
                    return "LENGTH_PREFIXED";
                }
            }

            buffer.readerIndex(readerIndex);

            // Check for ISO-8583 binary
            if (buffer.readableBytes() >= 2) {
                byte[] header = new byte[2];
                buffer.readBytes(header);
                if (isIso8583Binary(header)) {
                    return "ISO8583_BINARY";
                }
            }

            buffer.readerIndex(readerIndex);

            // Default to line-delimited text
            return "LINE_DELIMITED";

        } finally {
            buffer.readerIndex(readerIndex);
        }
    }

    private boolean isIso8583Binary(byte[] header) {
        // Check for common ISO-8583 MTI patterns
        return (header[0] == 0x01 || header[0] == 0x02) &&
               (header[1] >= 0x00 && header[1] <= 0x99);
    }

    private void setupPipeline(ChannelHandlerContext ctx, String protocol) {
        switch (protocol) {
            case "LENGTH_PREFIXED":
                ctx.pipeline().addAfter(ctx.name(), "lengthDecoder",
                    new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                // Binary handler will be added by TcpMockServer
                break;

            case "ISO8583_BINARY":
                ctx.pipeline().addAfter(ctx.name(), "iso8583Decoder",
                    new Iso8583BinaryDecoder());
                // Binary handler will be added by TcpMockServer
                break;

            default: // LINE_DELIMITED
                ctx.pipeline().addAfter(ctx.name(), "frameDecoder",
                    new DelimiterBasedFrameDecoder(8192, Delimiters.lineDelimiter()));
                ctx.pipeline().addAfter("frameDecoder", "stringDecoder", new StringDecoder());
                ctx.pipeline().addAfter("stringDecoder", "stringEncoder", new StringEncoder());
                break;
        }

        // Remove this handler
        ctx.pipeline().remove(this);
    }
}
