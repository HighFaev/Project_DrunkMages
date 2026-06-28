package io.github.drunkmages.common.net;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;

/**
 * Big-endian binary helpers per {@code networc_structure.md} §3.
 */
public final class Wire {

    private Wire() {
    }

    public static boolean readBool(ByteBuf buf) {
        return buf.readByte() != 0;
    }

    public static void writeBool(ByteBuf buf, boolean v) {
        buf.writeByte(v ? 1 : 0);
    }

    public static float readF32(ByteBuf buf) {
        return buf.readFloat();
    }

    public static void writeF32(ByteBuf buf, float v) {
        buf.writeFloat(v);
    }

    public static String readStr(ByteBuf buf) {
        int n = buf.readUnsignedShort();
        if (n == 0) {
            return "";
        }
        byte[] b = new byte[n];
        buf.readBytes(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    public static void writeStr(ByteBuf buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(b.length);
        buf.writeBytes(b);
    }

    /** Same as Wire string (u16-length UTF-8). */
    public static String readName(ByteBuf buf) {
        return readStr(buf);
    }

    public static void writeName(ByteBuf buf, String s) {
        writeStr(buf, s == null ? "" : s);
    }
}
