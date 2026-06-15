package io.github.drunkmages.common.net.udp;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UdpHeaderTest {

    @Test
    void headerBytesConstant() {
        assertEquals(15, UdpOpcodes.HEADER_BYTES);
    }

    @Test
    void roundTrip() {
        var buf = Unpooled.buffer();
        UdpHeader.write(buf, UdpOpcodes.C_INPUT, 42, 100, 3, 0xdeadbeefL);
        assertEquals(UdpOpcodes.HEADER_BYTES, buf.readableBytes());

        var hdr = UdpHeader.read(buf);
        assertEquals(UdpOpcodes.C_INPUT, hdr.type());
        assertEquals(42, hdr.seqUnsigned());
        assertEquals(100, hdr.tickUnsigned());
        assertEquals(3, hdr.playerIdUnsigned());
        assertEquals(0xdeadbeefL, hdr.matchIdUnsigned());
        assertEquals(0, buf.readableBytes());
    }
}
