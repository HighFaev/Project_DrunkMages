package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * Decodes the two server → client packet types:
 *
 * <pre>
 *  WELCOME  0x01  [i32 id]
 *  ROSTER   0x02  [i32 count]  ( [i32 id] [u16 nameLen] [utf8 name] ) * count
 * </pre>
 *
 * Handles fragmentation: resets the reader index and waits if the full
 * payload has not arrived yet.
 */
final class ServerPacketDecoder extends ByteToMessageDecoder {
    private static final byte TYPE_WELCOME = 0x01;
    private static final byte TYPE_ROSTER = 0x02;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) {
            return;
        }
        in.markReaderIndex();
        byte type = in.readByte();

        switch (type) {
            case TYPE_WELCOME -> {
                if (in.readableBytes() < 4) {
                    in.resetReaderIndex();
                    return;
                }
                out.add(new WelcomePacket(in.readInt()));
            }

            case TYPE_ROSTER -> {
                if (in.readableBytes() < 4) {
                    in.resetReaderIndex();
                    return;
                }
                int count = in.readInt();
                List<PlayerInfo> players = new ArrayList<>(count);

                for (int i = 0; i < count; i++) {
                    if (in.readableBytes() < 6) {
                        in.resetReaderIndex();
                        return;
                    }
                    int playerId = in.readInt();
                    int nameLen = in.readUnsignedShort();
                    if (in.readableBytes() < nameLen) {
                        in.resetReaderIndex();
                        return;
                    }
                    byte[] nameBytes = new byte[nameLen];
                    in.readBytes(nameBytes);
                    players.add(new PlayerInfo(playerId, new String(nameBytes, StandardCharsets.UTF_8)));
                }
                out.add( new RosterUpdate(List.copyOf(players)));
            }

            default -> {
                ctx.close();
            }
        }
    }
}
