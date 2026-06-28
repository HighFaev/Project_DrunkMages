package io.github.drunkmages.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HandshakeTest {

    @Test
    void storesNickname() {
        var hs = new Handshake("highfaev");
        assertEquals("highfaev", hs.nickname());
    }

    @Test
    void blankNicknameRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Handshake("   "));
    }

    @Test
    void nullNicknameRejected() {
        assertThrows(NullPointerException.class, () -> new Handshake(null));
    }
}
