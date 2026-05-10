package io.github.drunkmages.common.net.udp;

import io.netty.buffer.ByteBuf;

/**
 * Fixed UDP framing prefix (extended with {@code match_id} vs §5 doc text mismatch).
 *
 * Layout: {@code type(u8) | seq(u32) | tick(u32) | player_id(u16) | match_id(u32)}.<br/>
 * Client→server: {@code player_id} is sender.<br/>
 * Server→client: {@code player_id} is zero per spec commentary.
 */
public record UdpHeader(byte type, int seqUnsigned, int tickUnsigned, int playerIdUnsigned, long matchIdUnsigned) {

    public static void write(ByteBuf out, byte type, int seq, int tick, int playerId, long matchId) {
        out.writeByte(type);
        out.writeInt(seq);
        out.writeInt(tick);
        out.writeShort(playerId);
        out.writeInt((int) (matchId & 0xffffffffL));
    }

    /** Reads header and advances reader index; expects at least HEADER_BYTES readable. */
    public static UdpHeader read(ByteBuf in) {
        byte t = in.readByte();
        int seq = in.readInt();
        int tk = in.readInt();
        int pid = in.readUnsignedShort();
        long mid = Integer.toUnsignedLong(in.readInt());
        return new UdpHeader(t, seq, tk, pid, mid);
    }
}
