package io.pockethive.tcpmock.handler;

import io.pockethive.tcpmock.config.TcpMockConfig;
import io.pockethive.tcpmock.model.MessageTypeMapping;
import io.pockethive.tcpmock.model.MessageTypeMapping.WireProfile;
import io.pockethive.tcpmock.service.MessageTypeRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.FixedLengthFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * First handler in the pipeline. Resolves the wire framing profile for the connection
 * and installs the appropriate codec chain.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>If the highest-priority enabled mapping declares a non-AUTO {@code wireProfile},
 *       use it unconditionally — no byte inspection needed.</li>
 *   <li>Otherwise auto-detect from the first 4 bytes.</li>
 * </ol>
 *
 * <p>Not {@code @Sharable} — one instance per channel.
 */
public class ProtocolDetectionHandler extends ByteToMessageDecoder {

    private final TcpMockConfig config;
    private final MessageTypeRegistry registry;
    private boolean resolved = false;

    public ProtocolDetectionHandler(TcpMockConfig config, MessageTypeRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (resolved) {
            out.add(in.readBytes(in.readableBytes()));
            return;
        }

        // Try explicit wireProfile from highest-priority mapping first
        WireProfile explicit = resolveExplicitProfile();
        if (explicit != null && explicit != WireProfile.AUTO) {
            setupPipeline(ctx, explicit, null);
            resolved = true;
            out.add(in.readBytes(in.readableBytes()));
            return;
        }

        // Auto-detect — need at least 4 bytes
        if (in.readableBytes() < 4) return;

        WireProfile detected = autoDetect(in);
        Integer fixedLen = null;
        if (detected == WireProfile.FIXED_LENGTH) {
            fixedLen = resolveFixedFrameLength();
        }
        setupPipeline(ctx, detected, fixedLen);
        resolved = true;
        out.add(in.readBytes(in.readableBytes()));
    }

    // ── Profile resolution ────────────────────────────────────────────────────

    private WireProfile resolveExplicitProfile() {
        return registry.getSortedMappings().stream()
            .map(MessageTypeMapping::getWireProfile)
            .filter(p -> p != null)
            .findFirst()
            .orElse(null);
    }

    private Integer resolveFixedFrameLength() {
        return registry.getSortedMappings().stream()
            .map(MessageTypeMapping::getFixedFrameLength)
            .filter(l -> l != null && l > 0)
            .findFirst()
            .orElse(null);
    }

    private WireProfile autoDetect(ByteBuf buf) {
        int ri = buf.readerIndex();

        // STX byte at position 0 → STX/ETX binary
        if (buf.getByte(ri) == 0x02) {
            return WireProfile.STX_ETX;
        }

        // 2-byte big-endian length + ISO-8583 MTI (MC_2BYTE_LEN_BIN_BITMAP)
        int len2 = buf.getUnsignedShort(ri);
        byte mtiHigh = buf.getByte(ri + 2);
        if (len2 > 0 && len2 < 8192 && (mtiHigh == 0x01 || mtiHigh == 0x02)) {
            return WireProfile.LENGTH_PREFIX_2B;
        }

        // 4-byte big-endian length prefix
        int len4 = buf.getInt(ri);
        if (len4 > 0 && len4 < 65536) {
            return WireProfile.LENGTH_PREFIX_4B;
        }

        // Raw ISO-8583 binary (MTI first, no length prefix)
        if (mtiHigh == 0x01 || mtiHigh == 0x02) {
            return WireProfile.LENGTH_PREFIX_2B; // treat as 2B for safety
        }

        return WireProfile.LINE; // default: newline-delimited text
    }

    // ── Pipeline setup ────────────────────────────────────────────────────────

    private void setupPipeline(ChannelHandlerContext ctx, WireProfile profile, Integer fixedLen) {
        String after = ctx.name();

        switch (profile) {
            case LENGTH_PREFIX_2B -> {
                ctx.pipeline().addAfter(after, "lenDecoder",
                    new LengthFieldBasedFrameDecoder(8192, 0, 2, 0, 2));
                ctx.pipeline().addAfter("lenDecoder", "lenEncoder", new TwoByteLengthPrepender());
                ctx.pipeline().addAfter("lenEncoder", "strDecoder",
                    new StringDecoder(StandardCharsets.ISO_8859_1));
                ctx.pipeline().addAfter("strDecoder", "strEncoder",
                    new StringEncoder(StandardCharsets.ISO_8859_1));
            }
            case LENGTH_PREFIX_4B -> {
                ctx.pipeline().addAfter(after, "lenDecoder",
                    new LengthFieldBasedFrameDecoder(65536, 0, 4, 0, 4));
                ctx.pipeline().addAfter("lenDecoder", "lenEncoder", new FourByteLengthPrepender());
                ctx.pipeline().addAfter("lenEncoder", "strDecoder",
                    new StringDecoder(StandardCharsets.ISO_8859_1));
                ctx.pipeline().addAfter("strDecoder", "strEncoder",
                    new StringEncoder(StandardCharsets.ISO_8859_1));
            }
            case FIXED_LENGTH -> {
                int frameSize = (fixedLen != null && fixedLen > 0) ? fixedLen : 128;
                ctx.pipeline().addAfter(after, "fixedDecoder", new FixedLengthFrameDecoder(frameSize));
                ctx.pipeline().addAfter("fixedDecoder", "strDecoder", new StringDecoder());
                ctx.pipeline().addAfter("strDecoder", "strEncoder", new StringEncoder());
            }
            case STX_ETX -> {
                ctx.pipeline().addAfter(after, "stxEtxDecoder", new StxEtxFrameDecoder());
                ctx.pipeline().addAfter("stxEtxDecoder", "strDecoder",
                    new StringDecoder(StandardCharsets.ISO_8859_1));
                ctx.pipeline().addAfter("strDecoder", "strEncoder",
                    new StringEncoder(StandardCharsets.ISO_8859_1));
            }
            case FIRE_FORGET -> {
                // No response encoder needed — UnifiedTcpRequestHandler handles FIRE_FORGET
                ctx.pipeline().addAfter(after, "frameDecoder",
                    new MappingAwareFrameDecoder(registry, config.getValidation().getMaxMessageSize()));
                ctx.pipeline().addAfter("frameDecoder", "strDecoder", new StringDecoder());
                ctx.pipeline().addAfter("strDecoder", "strEncoder", new StringEncoder());
            }
            default -> { // LINE, DELIMITER, AUTO — mapping-driven text framing
                ctx.pipeline().addAfter(after, "frameDecoder",
                    new MappingAwareFrameDecoder(registry, config.getValidation().getMaxMessageSize()));
                ctx.pipeline().addAfter("frameDecoder", "strDecoder", new StringDecoder());
                ctx.pipeline().addAfter("strDecoder", "strEncoder", new StringEncoder());
            }
        }

        ctx.pipeline().remove(this);
    }

    // ── Outbound encoders ─────────────────────────────────────────────────────

    /**
     * Prepends a 2-byte big-endian length field to every outbound message.
     * Matches MC_2BYTE_LEN_BIN_BITMAP / LengthPrefix2BResponseReader.
     */
    static final class TwoByteLengthPrepender extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ByteBuf body = toBytes(ctx, msg);
            if (body == null) { ctx.write(msg, promise); return; }
            ByteBuf header = ctx.alloc().buffer(2);
            header.writeShort(body.readableBytes());
            ctx.write(Unpooled.wrappedBuffer(header, body), promise);
        }
    }

    /**
     * Prepends a 4-byte big-endian length field to every outbound message.
     */
    static final class FourByteLengthPrepender extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ByteBuf body = toBytes(ctx, msg);
            if (body == null) { ctx.write(msg, promise); return; }
            ByteBuf header = ctx.alloc().buffer(4);
            header.writeInt(body.readableBytes());
            ctx.write(Unpooled.wrappedBuffer(header, body), promise);
        }
    }

    private static ByteBuf toBytes(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf buf) return buf;
        if (msg instanceof String s) return Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.ISO_8859_1));
        return null;
    }
}
