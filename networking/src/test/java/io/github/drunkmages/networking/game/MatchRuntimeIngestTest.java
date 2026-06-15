package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;
import io.github.drunkmages.networking.MatchEndPacket;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRuntimeIngestTest {

    private static final InetAddress LOCAL;

    static {
        try {
            LOCAL = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void rejectsWrongMatchId() {
        var rt = runtime(0x100);
        var sender = new InetSocketAddress(LOCAL, 40_000);
        assertFalse(rt.ingest(sender, inputPacket(0x200L, 1, 1, 1f, 0f)));
    }

    @Test
    void rejectsUnknownPlayer() {
        var rt = runtime(0x100);
        var sender = new InetSocketAddress(LOCAL, 40_001);
        assertFalse(rt.ingest(sender, inputPacket(0x100L, 99, 1, 1f, 0f)));
    }

    @Test
    void rejectsIpMismatch() throws Exception {
        var rt = runtime(0x100);
        var wrongHost = new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 40_002);
        assertFalse(rt.ingest(wrongHost, inputPacket(0x100L, 1, 1, 1f, 0f)));
    }

    @Test
    void rejectsSeqReplay() throws Exception {
        var rt = runtime(0x100);
        var sender = new InetSocketAddress(LOCAL, 40_003);
        assertTrue(rt.ingest(sender, inputPacket(0x100L, 1, 5, 2f, 0f)));
        assertFalse(rt.ingest(sender, inputPacket(0x100L, 1, 4, 2f, 0f)));
    }

    @Test
    void acceptsInputAndStoresPayload() throws Exception {
        var rt = runtime(0x100);
        var sender = new InetSocketAddress(LOCAL, 40_004);
        assertTrue(rt.ingest(sender, inputPacket(0x100L, 1, 10, 3.5f, 1.25f)));
        assertTrue(rt.ingest(sender, inputPacket(0x100L, 1, 11, 0f, 0f)));
    }

    @Test
    void hasLineOfSightBlockedByWall() {
        assertFalse(MatchRuntime.hasLineOfSight(-100f, 0f, 100f, 0f));
        assertTrue(MatchRuntime.hasLineOfSight(-250f, -250f, -200f, -200f));
    }

    @Test
    void signedMatchBitsMatchesConstructor() {
        var rt = runtime(0xBEEF);
        assertEquals(0xBEEF, rt.signedMatchBits());
    }

    private static MatchRuntime runtime(int matchBits) {
        var roster = List.of(new MatchParticipant(1, 0f, 0f, LOCAL));
        return new MatchRuntime(
                matchBits,
                roster,
                Map.of(1, "p1"),
                (v, k, p) -> {},
                (MatchEndPacket ignored) -> {});
    }

    private static io.netty.buffer.ByteBuf inputPacket(
            long matchId, int playerId, int seq, float vx, float vy) {
        var buf = Unpooled.buffer();
        UdpHeader.write(buf, UdpOpcodes.C_INPUT, seq, 0, playerId, matchId);
        buf.writeFloat(0f);
        buf.writeFloat(0f);
        buf.writeFloat(vx);
        buf.writeFloat(vy);
        buf.writeFloat(0f);
        buf.writeByte(0);
        buf.writeByte(1);
        return buf;
    }
}
