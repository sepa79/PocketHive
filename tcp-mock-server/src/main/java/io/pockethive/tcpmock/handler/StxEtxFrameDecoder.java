package io.pockethive.tcpmock.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

/**
 * Decodes STX (0x02) ... ETX (0x03) framed binary messages.
 * Emits the content between STX and ETX inclusive.
 * Not {@code @Sharable} — one instance per channel.
 */
public class StxEtxFrameDecoder extends ByteToMessageDecoder {

    private static final byte STX = 0x02;
    private static final byte ETX = 0x03;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() > 0) {
            int base = in.readerIndex();

            // Find STX
            int stxPos = -1;
            for (int i = base; i < base + in.readableBytes(); i++) {
                if (in.getByte(i) == STX) { stxPos = i; break; }
            }
            if (stxPos < 0) { in.skipBytes(in.readableBytes()); return; }

            // Find ETX after STX
            int etxPos = -1;
            for (int i = stxPos + 1; i < base + in.readableBytes(); i++) {
                if (in.getByte(i) == ETX) { etxPos = i; break; }
            }
            if (etxPos < 0) return; // wait for more data

            // Skip bytes before STX
            in.readerIndex(stxPos);
            // Emit STX..ETX inclusive
            out.add(in.readRetainedSlice(etxPos - stxPos + 1));
        }
    }
}
