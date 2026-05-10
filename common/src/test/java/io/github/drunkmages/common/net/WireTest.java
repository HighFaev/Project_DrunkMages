package io.github.drunkmages.common.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class WireTest {

    @Test
    void strRoundTrip() {
        roundTrip("");
        roundTrip("mark");
        roundTrip("Юникод");
    }

    @Test
    void boolRoundTrip() {
        var buf = Unpooled.buffer();
        Wire.writeBool(buf, false);
        Wire.writeBool(buf, true);
        assertFalse(Wire.readBool(buf));
        assertTrue(Wire.readBool(buf));
    }

    private static void roundTrip(String s) {
        var buf = Unpooled.buffer();
        Wire.writeStr(buf, s);
        buf.readerIndex(0);
        assertEquals(s, Wire.readStr(buf));
    }
}
