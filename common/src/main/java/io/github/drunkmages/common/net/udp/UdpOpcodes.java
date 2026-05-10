package io.github.drunkmages.common.net.udp;

/** UDP game packet IDs from {@code networc_structure.md} §5 plus shared header conventions. */
public final class UdpOpcodes {

    /** Client → server. */
    public static final byte C_INPUT = 0x01;
    public static final byte C_SHOOT = 0x02;
    public static final byte C_ITEM_PICKUP = 0x03;
    public static final byte C_ITEM_USE = 0x04;
    public static final byte C_ITEM_DROP = 0x05;
    public static final byte C_RELOAD = 0x06;
    public static final byte C_PING = 0x07;

    /** Server → client. */
    public static final byte S_WORLD_SNAPSHOT = (byte) 0x81;
    public static final byte S_HIT_CONFIRM = (byte) 0x82;
    public static final byte S_PLAYER_DIED_UDP = (byte) 0x83;
    public static final byte S_ZONE_STATE = (byte) 0x84;
    public static final byte S_ITEM_SPAWNED = (byte) 0x85;
    public static final byte S_ITEM_DESPAWNED = (byte) 0x86;
    public static final byte S_ITEM_PICKUP_RESULT = (byte) 0x87;
    public static final byte S_PROJECTILE_SPAWN = (byte) 0x88;
    public static final byte S_PONG = (byte) 0x89;

    /** UDP header byte length: type(u8)+seq(u32)+tick(u32)+player(u16)+match(u32). */
    public static final int HEADER_BYTES = 15;

    /** Entity types inside snapshots. */
    public static final byte ENTITY_PLAYER = 0x01;
    public static final byte ENTITY_PROJECTILE = 0x02;
    public static final byte ENTITY_ITEM_ON_GROUND = 0x03;

    private UdpOpcodes() {
    }
}
