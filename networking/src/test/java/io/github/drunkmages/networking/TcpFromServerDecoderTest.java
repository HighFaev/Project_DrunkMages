package io.github.drunkmages.networking;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.github.drunkmages.common.net.Wire;
import io.github.drunkmages.common.net.tcp.TcpOpcodes;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpFromServerDecoderTest {

    @Test
    void welcomeRoundTrip() {
        var pkt = decode(LobbyTcpOutbound.welcome(ByteBufAllocator.DEFAULT, 7));
        assertInstanceOf(WelcomePacket.class, pkt);
        assertEquals(7, ((WelcomePacket) pkt).id());
    }

    @Test
    void rosterEmptyAndUnicode() {
        var empty = decode(LobbyTcpOutbound.rosterBroadcast(ByteBufAllocator.DEFAULT, List.of()));
        assertInstanceOf(RosterUpdate.class, empty);
        assertEquals(0, ((RosterUpdate) empty).players().size());

        var roster = decode(LobbyTcpOutbound.rosterBroadcast(
                ByteBufAllocator.DEFAULT,
                List.of(new PlayerInfo(1, "żółć", true), new PlayerInfo(2, "bob", false))));
        assertInstanceOf(RosterUpdate.class, roster);
        var players = ((RosterUpdate) roster).players();
        assertEquals(2, players.size());
        assertEquals("żółć", players.get(0).nickname());
        assertTrue(players.get(0).lobbyReady());
        assertFalse(players.get(1).lobbyReady());
    }

    @Test
    void matchFoundRoundTrip() {
        var pkt = decode(LobbyTcpOutbound.matchFound(
                ByteBufAllocator.DEFAULT, 0x1234L, "127.0.0.1", 25566, 2, 4, 10f, -5f, 5));
        assertInstanceOf(MatchFoundPacket.class, pkt);
        var mf = (MatchFoundPacket) pkt;
        assertEquals(0x1234L, mf.matchId());
        assertEquals("127.0.0.1", mf.udpHost());
        assertEquals(25566, mf.udpPort());
        assertEquals(2, mf.localMatchPlayerId());
        assertEquals(4, mf.playerCount());
        assertEquals(10f, mf.spawnX(), 1e-4f);
        assertEquals(-5f, mf.spawnY(), 1e-4f);
        assertEquals(5, mf.countdownSeconds());
    }

    @Test
    void matchCountdownAndStartRoundTrip() {
        var cd = decode(LobbyTcpOutbound.matchCountdown(ByteBufAllocator.DEFAULT, 3));
        assertInstanceOf(MatchCountdownPacket.class, cd);
        assertEquals(3, ((MatchCountdownPacket) cd).secondsLeft());

        var st = decode(LobbyTcpOutbound.matchStart(ByteBufAllocator.DEFAULT, 100, 9_000));
        assertInstanceOf(MatchStartPacket.class, st);
        var ms = (MatchStartPacket) st;
        assertEquals(100, ms.startTick());
        assertEquals(9_000, ms.serverTimeMs());
    }

    @Test
    void playerDiedRoundTrip() {
        var pkt = decode(LobbyTcpOutbound.playerDied(
                ByteBufAllocator.DEFAULT, 2, "victim", 5, "killer", 3));
        assertInstanceOf(PlayerDiedTcpPacket.class, pkt);
        var pd = (PlayerDiedTcpPacket) pkt;
        assertEquals(2, pd.playerId());
        assertEquals("victim", pd.victimNickname());
        assertEquals(5, pd.killerId());
        assertEquals("killer", pd.killerNickname());
        assertEquals(3, pd.placement());
    }

    @Test
    void matchEndWithStatsRoundTrip() {
        var stats = List.of(
                new MatchStatEntry(1, 1, 2, 50, 600),
                new MatchStatEntry(2, 2, 0, 10, 300));
        var pkt = decode(LobbyTcpOutbound.matchEnd(
                ByteBufAllocator.DEFAULT, 99L, 1, "winner", 1200, stats));
        assertInstanceOf(MatchEndPacket.class, pkt);
        var me = (MatchEndPacket) pkt;
        assertEquals(99L, me.matchId());
        assertEquals(1, me.winnerId());
        assertEquals("winner", me.winnerNickname());
        assertEquals(1200, me.durationTicks());
        assertEquals(2, me.stats().size());
        assertEquals(50, me.stats().get(0).damageDealt());
    }

    @Test
    void kickRoundTrip() {
        var buf = Unpooled.buffer();
        buf.writeByte(TcpOpcodes.S_KICK);
        Wire.writeStr(buf, "duplicate nickname");
        var pkt = decode(buf);
        assertInstanceOf(KickPacket.class, pkt);
        assertEquals("duplicate nickname", ((KickPacket) pkt).reason());
    }

    @Test
    void malformedRosterCountClosesChannel() {
        var bad = Unpooled.buffer();
        bad.writeByte(TcpOpcodes.S_ROSTER_UPDATE);
        bad.writeInt(50_000);
        var ch = new EmbeddedChannel(new TcpFromServerDecoder());
        ch.writeInbound(bad);
        assertFalse(ch.isOpen());
    }

    private static Object decode(io.netty.buffer.ByteBuf frame) {
        var ch = new EmbeddedChannel(new TcpFromServerDecoder());
        assertTrue(ch.writeInbound(frame));
        Object pkt = ch.readInbound();
        assertNotNull(pkt);
        return pkt;
    }
}
