package io.github.drunkmages.networking;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: roster frames must decode when the TCP chunk ends exactly on the last nickname byte
 * (otherwise the lobby list stays empty on some clients).
 */
class TcpFromServerDecoderRosterTest {

    @Test
    void rosterDecodesWhenFrameHasNoTrailingBytes() {
        var frame =
                LobbyTcpOutbound.rosterBroadcast(
                        ByteBufAllocator.DEFAULT,
                        List.of(new PlayerInfo(4, "mark", true), new PlayerInfo(9, "bob", false)));

        var ch = new EmbeddedChannel(new TcpFromServerDecoder());
        assertTrue(ch.writeInbound(frame));

        RosterUpdate ru = ch.readInbound();
        assertNotNull(ru);
        assertEquals(2, ru.players().size());
        assertEquals(4, ru.players().get(0).id());
        assertEquals("mark", ru.players().get(0).nickname());
        assertTrue(ru.players().get(0).lobbyReady());
        assertEquals(9, ru.players().get(1).id());
        assertEquals("bob", ru.players().get(1).nickname());
        assertFalse(ru.players().get(1).lobbyReady());
    }
}
