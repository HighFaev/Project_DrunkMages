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

    static ByteBuf encode(ByteBufAllocator alloc, int srvSeq, int tick, int matchSignedBits, PlayerSimState[] players, java.util.List<MatchRuntime.ServerItem> items) {
        int est = UdpOpcodes.HEADER_BYTES + 2;
        est += players.length * 60 + (items.size() * 15);
        ByteBuf payload = alloc.buffer(est);
        UdpHeader.write(
                payload, UdpOpcodes.S_WORLD_SNAPSHOT, srvSeq, tick, 0, Integer.toUnsignedLong(matchSignedBits));
        payload.writeByte(1);

        // Calculate total entities
        int totalEntities = Math.min(players.length + items.size(), 255);
        payload.writeByte(totalEntities);
        int encodedCount = 0;
        // 1. Write Players (Only loop over the actual players array!)
        int playersToWrite = Math.min(players.length, 255);
        for (int i = 0; i < players.length && encodedCount < 255; i++) {
            PlayerSimState ps = players[i];
            payload.writeShort(ps.entityId);
            payload.writeByte(UdpOpcodes.ENTITY_PLAYER);
            payload.writeFloat(ps.posX);
            payload.writeFloat(ps.posY);
            payload.writeFloat(ps.aimAngle);
            payload.writeByte(ps.hp > 0 ? 0 : 2); // alive
            payload.writeShort(ps.hp);
            payload.writeShort(ps.maxHp);
            payload.writeByte(ps.selectedSlot);       // 1 byte
            payload.writeByte(ps.isShooting ? 3 : 0); // 1 byte
            for (int slot = 0; slot < 5; slot++) {
                payload.writeShort(ps.inventory[slot]); // 10 bytes total
            }
            payload.writeMedium(0);                  // 3 bytes padding

            encodedCount++;
        }

        // 2. Write Ground Items
        for (int i = 0; i < items.size() && encodedCount < 255; i++) {
            MatchRuntime.ServerItem item = items.get(i);
            payload.writeShort(item.entityId);
            payload.writeByte(UdpOpcodes.ENTITY_ITEM_ON_GROUND);
            payload.writeFloat(item.x);
            payload.writeFloat(item.y);
            payload.writeShort(item.itemType);
            payload.writeByte(1); // quantity
        }

        return payload;
    }
}
