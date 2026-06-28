package io.github.drunkmages.networking;

import io.github.drunkmages.common.Handshake;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpFromClientDecoderTest {

    @Test
    void decodesHandshakeReadyAndLeave() {
        var ch = new EmbeddedChannel(new TcpFromClientDecoder());

        assertTrue(ch.writeInbound(LobbyTcpOutbound.handshake(ByteBufAllocator.DEFAULT, "alice")));
        var hs = ch.readInbound();
        assertInstanceOf(Handshake.class, hs);
        assertEquals("alice", ((Handshake) hs).nickname());

        assertTrue(ch.writeInbound(LobbyTcpOutbound.playerReady(ByteBufAllocator.DEFAULT)));
        assertEquals(ReadyC2S.INSTANCE, ch.readInbound());

        assertTrue(ch.writeInbound(LobbyTcpOutbound.leaveLobby(ByteBufAllocator.DEFAULT)));
        assertEquals(LeaveLobbyC2S.INSTANCE, ch.readInbound());
    }

    @Test
    void handshakeSurvivesFragmentation() {
        var full = LobbyTcpOutbound.handshake(ByteBufAllocator.DEFAULT, "long_nickname_here");
        byte[] bytes = new byte[full.readableBytes()];
        full.readBytes(bytes);
        full.release();

        var ch = new EmbeddedChannel(new TcpFromClientDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 0, 3));
        assertNull(ch.readInbound());

        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 3, bytes.length - 3));
        var hs = ch.readInbound();
        assertInstanceOf(Handshake.class, hs);
        assertEquals("long_nickname_here", ((Handshake) hs).nickname());
    }

    @Test
    void invalidOpcodeClosesChannel() {
        var ch = new EmbeddedChannel(new TcpFromClientDecoder());
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[] {(byte) 0x99}));
        assertFalse(ch.isOpen());
    }
}
