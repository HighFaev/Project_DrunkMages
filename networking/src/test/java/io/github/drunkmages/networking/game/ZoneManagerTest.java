package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpOpcodes;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneManagerTest {

    @Test
    void initialPhaseUsesExpectedDamage() {
        var zone = new ZoneManager();
        assertEquals(0, zone.phase);
        assertEquals(1, zone.damagePerTick);
        assertTrue(zone.curRadius > zone.nextRadius);
    }

    @Test
    void encodeWritesZoneOpcodeAndHeader() {
        var zone = new ZoneManager();
        var buf = zone.encode(ByteBufAllocator.DEFAULT, 7, 0x55);
        assertEquals(UdpOpcodes.HEADER_BYTES + 35, buf.readableBytes());
        assertEquals(UdpOpcodes.S_ZONE_STATE, buf.readByte());
        assertEquals(7, buf.readInt()); // seq
        assertEquals(0, buf.readInt()); // tick
        assertEquals(0, buf.readUnsignedShort()); // player id
        assertEquals(0x55, buf.readInt()); // match id bits
        assertEquals(zone.curX, buf.readFloat(), 1e-4f);
        assertEquals(zone.curY, buf.readFloat(), 1e-4f);
        assertEquals(zone.curRadius, buf.readFloat(), 1e-4f);
        buf.release();
    }

    @Test
    void shrinkEventuallyReducesRadius() {
        var zone = new ZoneManager();
        float initial = zone.curRadius;
        for (int i = 0; i <= zone.shrinkEndTick; i++) {
            zone.tick();
        }
        assertTrue(zone.curRadius <= initial);
        assertTrue(zone.phase >= 1);
    }
}
