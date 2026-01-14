package io.pockethive.tcpmock.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;

public class Iso8583BinaryDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (in.readableBytes() < 2) return;

        // Read ISO-8583 message length (first 2 bytes)
        int length = in.readUnsignedShort();

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        // Read the message
        byte[] data = new byte[length];
        in.readBytes(data);

        // Convert to hex string for processing
        StringBuilder hex = new StringBuilder();
        for (byte b : data) {
            hex.append(String.format("%02X", b));
        }

        out.add(hex.toString());
    }
}
