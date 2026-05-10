package io.github.drunkmages.networking;

import io.github.drunkmages.common.Handshake;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes lobby-layer client → server frames (leading type byte §4).
 */
final class TcpFromClientDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) {
            return;
        }
        in.markReaderIndex();
        int type = in.readUnsignedByte();
        switch (type) {
            case 0x01 -> {
                if (in.readableBytes() < 2) {
                    in.resetReaderIndex();
                    return;
                }
                int nameLen = in.getUnsignedShort(in.readerIndex());
                if (in.readableBytes() < 2 + nameLen) {
                    in.resetReaderIndex();
                    return;
                }
                in.skipBytes(2);
                byte[] raw = new byte[nameLen];
                in.readBytes(raw);
                out.add(new Handshake(new String(raw, StandardCharsets.UTF_8)));
            }
            case 0x02 -> out.add(ReadyC2S.INSTANCE);
            case 0x03 -> out.add(LeaveLobbyC2S.INSTANCE);
            default -> ctx.close();
        }
    }
}
