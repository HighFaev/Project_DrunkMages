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

    @Test
    void f32RoundTrip() {
        var buf = Unpooled.buffer();
        Wire.writeF32(buf, -3.5f);
        Wire.writeF32(buf, 42.25f);
        buf.readerIndex(0);
        assertEquals(-3.5f, Wire.readF32(buf), 1e-5f);
        assertEquals(42.25f, Wire.readF32(buf), 1e-5f);
    }

    @Test
    void nameNullWritesEmpty() {
        var buf = Unpooled.buffer();
        Wire.writeName(buf, null);
        buf.readerIndex(0);
        assertEquals("", Wire.readName(buf));
    }

    @Test
    void nameRoundTripUnicode() {
        var buf = Unpooled.buffer();
        Wire.writeName(buf, "gracz_żółć");
        buf.readerIndex(0);
        assertEquals("gracz_żółć", Wire.readName(buf));
    }

    private static void roundTrip(String s) {
        var buf = Unpooled.buffer();
        Wire.writeStr(buf, s);
        buf.readerIndex(0);
        assertEquals(s, Wire.readStr(buf));
    }
}
