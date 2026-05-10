package io.github.drunkmages.networking;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.net.Wire;
import io.github.drunkmages.common.net.tcp.TcpOpcodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Collection;

final class LobbyTcpOutbound {

    private LobbyTcpOutbound() {
    }

    static ByteBuf welcome(ByteBufAllocator alloc, int playerId) {
        var b = alloc.buffer(5);
        b.writeByte(TcpOpcodes.S_WELCOME);
        b.writeInt(playerId);
        return b;
    }

    /** Broadcast buffer: roster for all recipients. Caller must retain if fan-out needed. */
    static ByteBuf rosterBroadcast(ByteBufAllocator alloc, Collection<PlayerInfo> players) {
        int size = 5;
        for (PlayerInfo p : players) {
            size += 4 + 2 + p.nickname().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        var out = alloc.buffer(size);
        out.writeByte(TcpOpcodes.S_ROSTER_UPDATE);
        out.writeInt(players.size());
        for (PlayerInfo p : players) {
            out.writeInt(p.id());
            Wire.writeStr(out, p.nickname());
        }
        return out;
    }

    static ByteBuf matchFound(ByteBufAllocator alloc, long matchId, String udpHost, int udpPort,
            int localMatchPlayerId, int playerCount, float spawnX, float spawnY, int countdownSeconds) {
        String hostSafe = udpHost == null ? "" : udpHost;
        var b = alloc.buffer(32 + hostSafe.length() * 4);
        b.writeByte(TcpOpcodes.S_MATCH_FOUND);
        b.writeInt((int) (matchId & 0xffffffffL));
        Wire.writeStr(b, hostSafe);
        b.writeShort(udpPort);
        b.writeShort(localMatchPlayerId);
        b.writeByte(playerCount & 0xff);
        b.writeFloat(spawnX);
        b.writeFloat(spawnY);
        b.writeByte(countdownSeconds & 0xff);
        return b;
    }

    static ByteBuf matchCountdown(ByteBufAllocator alloc, int secondsLeft) {
        var b = alloc.buffer(2);
        b.writeByte(TcpOpcodes.S_MATCH_COUNTDOWN);
        b.writeByte(secondsLeft & 0xff);
        return b;
    }

    static ByteBuf matchStart(ByteBufAllocator alloc, int startTick, int serverTimeMs) {
        var b = alloc.buffer(9);
        b.writeByte(TcpOpcodes.S_MATCH_START);
        b.writeInt(startTick);
        b.writeInt(serverTimeMs);
        return b;
    }

    /** Handshake C→S. */
    static ByteBuf handshake(ByteBufAllocator alloc, String nickname) {
        byte[] nick = nickname.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var b = alloc.buffer(1 + 2 + nick.length);
        b.writeByte(TcpOpcodes.C_HANDSHAKE);
        Wire.writeStr(b, nickname);
        return b;
    }

    static ByteBuf playerReady(ByteBufAllocator alloc) {
        var b = alloc.buffer(1);
        b.writeByte(TcpOpcodes.C_PLAYER_READY);
        return b;
    }

    static ByteBuf leaveLobby(ByteBufAllocator alloc) {
        var b = alloc.buffer(1);
        b.writeByte(TcpOpcodes.C_PLAYER_LEAVE_LOBBY);
        return b;
    }
}
