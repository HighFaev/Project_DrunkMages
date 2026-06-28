package io.github.drunkmages.networking;

import java.util.ArrayList;
import java.util.List;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.github.drunkmages.common.net.Wire;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

/**
 * Decodes server → client lobby packets (§4.3). Fragmentation-safe.
 */
final class TcpFromServerDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 1) {
            return;
        }
        in.markReaderIndex();
        int type = in.readUnsignedByte();

        switch (type) {
            case 0x81 -> {
                if (in.readableBytes() < 4) {
                    rewind(in);
                    return;
                }
                out.add(new WelcomePacket(in.readInt()));
            }
            case 0x82 -> decodeRoster(ctx, in, out);
            case 0x83 -> decodeMatchFound(ctx, in, out);
            case 0x84 -> {
                if (in.readableBytes() < 1) {
                    rewind(in);
                    return;
                }
                out.add(new MatchCountdownPacket(in.readUnsignedByte()));
            }
            case 0x85 -> {
                if (in.readableBytes() < 8) {
                    rewind(in);
                    return;
                }
                out.add(new MatchStartPacket(in.readInt(), in.readInt()));
            }
            case 0x86 -> decodePlayerDied(ctx, in, out);
            case 0x87 -> decodeMatchEnd(ctx, in, out);
            case 0x88 -> decodeKick(ctx, in, out);
            default -> ctx.close();
        }
    }

    private static void decodeRoster(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            rewind(in);
            return;
        }
        int count = in.readInt();
        if (count < 0 || count > 10_000) {
            ctx.close();
            return;
        }
        List<PlayerInfo> players = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            /* playerId + u16 length prefix + UTF-8 payload */
            if (in.readableBytes() < 4 + 2) {
                rewind(in);
                return;
            }
            int playerId = in.readInt();
            int nameLen = in.getUnsignedShort(in.readerIndex());
            if (in.readableBytes() < 2 + nameLen) {
                rewind(in);
                return;
            }
            String nick = Wire.readStr(in);
            if (!in.isReadable(1)) {
                rewind(in);
                return;
            }
            boolean lobbyReady = in.readUnsignedByte() != 0;
            players.add(new PlayerInfo(playerId, nick, lobbyReady));
        }
        out.add(new RosterUpdate(List.copyOf(players)));
    }

    private static void decodeMatchFound(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            rewind(in);
            return;
        }
        long mid = Integer.toUnsignedLong(in.readInt());
        String host = readStrFully(ctx, in);
        if (host == null) {
            rewind(in);
            return;
        }
        if (in.readableBytes() < 2 + 2 + 1 + 8 + 1) {
            rewind(in);
            return;
        }
        int udpPort = in.readUnsignedShort();
        int localPid = in.readUnsignedShort();
        int cnt = in.readUnsignedByte();
        float sx = in.readFloat();
        float sy = in.readFloat();
        int cds = in.readUnsignedByte();
        out.add(new MatchFoundPacket(mid, host, udpPort, localPid, cnt, sx, sy, cds));
    }

    private static void decodePlayerDied(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        if (in.readableBytes() < 4) return;
        int victim = in.readUnsignedShort();
        String victimNick = readStrFully(ctx, in);
        if (victimNick == null) { rewind(in); return; }
        if (in.readableBytes() < 2) { rewind(in); return; }
        int killer = in.readUnsignedShort();
        String killerNick = readStrFully(ctx, in);
        if (killerNick == null) { rewind(in); return; }
        if (in.readableBytes() < 1) { rewind(in); return; }
        int placement = in.readUnsignedByte();
        out.add(new PlayerDiedTcpPacket(victim, victimNick, killer, killerNick, placement));
    }

    private static void decodeMatchEnd(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4 + 2 + 2) {
            rewind(in);
            return;
        }
        long matchId = Integer.toUnsignedLong(in.readInt());
        int winnerId = in.readUnsignedShort();
        String winNick = readStrFully(ctx, in);
        if (winNick == null) {
            rewind(in);
            return;
        }
        if (in.readableBytes() < 4 + 1) {
            rewind(in);
            return;
        }
        int durationTicks = in.readInt();
        int playerCount = in.readUnsignedByte();
        if (playerCount < 0) {
            ctx.close();
            return;
        }
        List<MatchStatEntry> stats = new ArrayList<>(Math.min(playerCount, 256));
        for (int i = 0; i < playerCount; i++) {
            if (in.readableBytes() < 2 + 1 + 2 + 2 + 4) {
                rewind(in);
                return;
            }
            int pid = in.readUnsignedShort();
            int placement = in.readUnsignedByte();
            int kills = in.readUnsignedShort();
            int dmg = in.readUnsignedShort();
            long surv = Integer.toUnsignedLong(in.readInt());
            stats.add(new MatchStatEntry(pid, placement, kills, dmg, surv));
        }
        out.add(new MatchEndPacket(matchId, winnerId, winNick, durationTicks, List.copyOf(stats)));
    }

    private static void decodeKick(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 2) {
            rewind(in);
            return;
        }
        String reason = readStrFully(ctx, in);
        if (reason == null) {
            rewind(in);
            return;
        }
        out.add(new KickPacket(reason));
    }

    /**
     * @return {@code null} if not yet complete.
     */
    private static String readStrFully(ChannelHandlerContext ctx, ByteBuf in) {
        if (in.readableBytes() < 2) {
            return null;
        }
        int nameLen = in.getUnsignedShort(in.readerIndex());
        if (nameLen > 65_535) {
            ctx.close();
            return null;
        }
        if (in.readableBytes() < 2 + nameLen) {
            return null;
        }
        return Wire.readStr(in);
    }

    private static void rewind(ByteBuf in) {
        in.resetReaderIndex();
    }
}
