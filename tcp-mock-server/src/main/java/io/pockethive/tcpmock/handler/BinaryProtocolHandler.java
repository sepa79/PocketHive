package io.pockethive.tcpmock.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import java.util.List;

public class BinaryProtocolHandler extends MessageToMessageCodec<ByteBuf, String> {

    @Override
    protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
        ByteBuf buffer = ctx.alloc().buffer();
        byte[] data = msg.getBytes();
        buffer.writeInt(data.length);
        buffer.writeBytes(data);
        out.add(buffer);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        byte[] data = new byte[msg.readableBytes()];
        msg.readBytes(data);
        out.add(new String(data));
    }
}
