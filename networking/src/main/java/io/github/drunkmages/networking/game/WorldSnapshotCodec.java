package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

final class WorldSnapshotCodec {

    private WorldSnapshotCodec() {
    }

    /**
     * One full WORLD_SNAPSHOT (§5.4) suitable for multicast (§5 PLAYER encoding §5.5 MVP subset).
     */
    /**
     * @param matchSignedBits bitwise {@code u32 match_id} as stored during {@code MATCH_FOUND}.
     */

    static ByteBuf encode(ByteBufAllocator alloc, int srvSeq, int tick, int matchSignedBits, PlayerSimState[] players) {
        int est = UdpOpcodes.HEADER_BYTES + 2;
        est += players.length * 60;
        ByteBuf payload = alloc.buffer(est);
        UdpHeader.write(
                payload, UdpOpcodes.S_WORLD_SNAPSHOT, srvSeq, tick, 0, Integer.toUnsignedLong(matchSignedBits));
        payload.writeByte(1); /* full snapshot flag */
        int n = Math.min(players.length, 255);
        payload.writeByte(n);
        for (int i = 0; i < n; i++) {
            PlayerSimState ps = players[i];
            payload.writeShort(ps.entityId);
            payload.writeByte(UdpOpcodes.ENTITY_PLAYER);
            payload.writeFloat(ps.posX);
            payload.writeFloat(ps.posY);
            payload.writeFloat(ps.aimAngle);
            payload.writeByte(ps.hp > 0 ? 0 : 2); // alive
            payload.writeShort(ps.hp);
            payload.writeShort(ps.maxHp);
            payload.writeShort(0); // shield
            payload.writeShort(0); // max shield
            payload.writeByte(0); // held slot
            payload.writeByte(ps.isShooting ? 3 : 0); // anim
            payload.writeByte(0); // inv weapon 0
            payload.writeByte(0);
            payload.writeByte(0); // ammo
            payload.writeByte(0);
        }
        return payload;
    }
}
