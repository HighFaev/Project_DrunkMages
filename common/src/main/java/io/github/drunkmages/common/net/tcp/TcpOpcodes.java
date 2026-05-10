package io.github.drunkmages.common.net.tcp;

/** TCP lobby packet IDs from {@code networc_structure.md} §4. */
public final class TcpOpcodes {
    /** Client → server. */
    public static final byte C_HANDSHAKE = 0x01;
    public static final byte C_PLAYER_READY = 0x02;
    public static final byte C_PLAYER_LEAVE_LOBBY = 0x03;

    /** Server → client. */
    public static final byte S_WELCOME = (byte) 0x81;
    public static final byte S_ROSTER_UPDATE = (byte) 0x82;
    public static final byte S_MATCH_FOUND = (byte) 0x83;
    public static final byte S_MATCH_COUNTDOWN = (byte) 0x84;
    public static final byte S_MATCH_START = (byte) 0x85;
    public static final byte S_PLAYER_DIED_TCP = (byte) 0x86;
    public static final byte S_MATCH_END = (byte) 0x87;
    public static final byte S_KICK = (byte) 0x88;

    private TcpOpcodes() {
    }
}
