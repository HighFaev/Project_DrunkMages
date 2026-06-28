package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpOpcodes;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSnapshotCodecTest {

    @Test
    void encodesPlayersAndGroundItems() {
        var p1 = new PlayerSimState(1, 1f, 2f);
        p1.posX = 10f;
        p1.posY = -3f;
        p1.aimAngle = 1.2f;
        p1.inventory[0] = 1;

        var item = new MatchRuntime.ServerItem();
        item.entityId = 50;
        item.itemType = 4;
        item.x = 5f;
        item.y = 6f;

        var buf = WorldSnapshotCodec.encode(
                ByteBufAllocator.DEFAULT, 3, 42, 0xABCD, new PlayerSimState[] {p1}, List.of(item));

        assertEquals(UdpOpcodes.S_WORLD_SNAPSHOT, buf.readByte());
        assertEquals(3, buf.readInt());
        assertEquals(42, buf.readInt());
        assertEquals(0, buf.readUnsignedShort());
        assertEquals(0xABCD, buf.readInt());
        assertEquals(1, buf.readUnsignedByte());
        assertEquals(2, buf.readUnsignedByte());
        assertTrue(buf.readableBytes() > UdpOpcodes.HEADER_BYTES);
        buf.release();
    }

    @Test
    void entityCountCappedAt255() {
        var players = new PlayerSimState[300];
        for (int i = 0; i < players.length; i++) {
            players[i] = new PlayerSimState(i + 1, 0f, 0f);
        }
        var buf = WorldSnapshotCodec.encode(
                ByteBufAllocator.DEFAULT, 1, 1, 1, players, List.of());
        buf.skipBytes(UdpOpcodes.HEADER_BYTES + 1);
        assertEquals(255, buf.readUnsignedByte());
        buf.release();
    }
}
