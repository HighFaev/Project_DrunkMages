package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * Authoritative simulation for one match instance. Intended to be driven only from the
 * {@link Channel#eventLoop()} that owns the UDP transport (single-threaded execution).
 */
public final class MatchRuntime {

    /** Spec §10: provisional speed cap pending full collision / stamina systems. */

    static final float MAX_SPEED = 42f;

    private static final float TICK_DELTA = 0.05f;

    private final int matchWireSignedInt;

    private final long assignedMatchUnsignedLong;

    private volatile int authoritativeTick;

    /** Monotonic server-authored UDP sequence numbers for downlink frames. */

    private int outboundSeqCounter;

    private final int maxEntityId;

    /** Dense iteration order mirroring MATCH roster order. */

    private final PlayerSimState[] rosterOrdered;

    /** Random access indexed by entity id. */

    private final PlayerSimState[] byEntityId;

    private final InetAddress[] tcpGate;

    private final InetSocketAddress[] udpBoundSlot;

    private final HashMap<Integer, Integer> lastSeqByEntity = new HashMap<>();

    private final HashMap<Integer, ClientInputPayload> latestInputs = new HashMap<>();

    public MatchRuntime(int matchWireSignedInt, List<MatchParticipant> rosterUnsorted) {

        Objects.requireNonNull(rosterUnsorted);

        List<MatchParticipant> roster =
                rosterUnsorted.stream()
                        .sorted(Comparator.comparingInt(MatchParticipant::matchLocalPlayerId))
                        .toList();

        if (roster.isEmpty()) {
            throw new IllegalArgumentException("empty roster");
        }

        maxEntityId = roster.stream().mapToInt(MatchParticipant::matchLocalPlayerId).max().orElseThrow();

        this.matchWireSignedInt = matchWireSignedInt;

        assignedMatchUnsignedLong = Integer.toUnsignedLong(matchWireSignedInt);

        byEntityId = new PlayerSimState[maxEntityId + 1];

        rosterOrdered = new PlayerSimState[roster.size()];

        tcpGate = new InetAddress[maxEntityId + 1];

        udpBoundSlot = new InetSocketAddress[maxEntityId + 1];

        int ox = 0;

        for (MatchParticipant mp : roster) {

            int eid = mp.matchLocalPlayerId();

            InetAddress ipa = Objects.requireNonNull(mp.tcpRemoteIp(), "TCP remote IP");

            PlayerSimState st = new PlayerSimState(eid, mp.spawnX(), mp.spawnY());

            rosterOrdered[ox++] = st;

            byEntityId[eid] = st;

            tcpGate[eid] = ipa;

            udpBoundSlot[eid] = null;
        }

        lastSeqByEntity.clear();
    }

    public int signedMatchBits() {

        return matchWireSignedInt;
    }

    /** Integrate snapshots + multicast WORLD_SNAPSHOT to every bound UDP endpoint. */

    public void advanceAndMulticast(Channel udpChannel) {

        authoritativeTick++;

        for (PlayerSimState ps : rosterOrdered) {

            ClientInputPayload pending = latestInputs.get(ps.entityId);

            applyMotion(ps, pending);
        }

        outboundSeqCounter++;

        ByteBuf snap =
                WorldSnapshotCodec.encode(
                        udpChannel.alloc(),
                        outboundSeqCounter,

                        authoritativeTick,

                        signedMatchBits(),

                        rosterOrdered);


        for (int eid = 1; eid <= maxEntityId; eid++) {

            InetSocketAddress dest = udpBoundSlot[eid];

            if (dest == null) {
                continue;
            }

            udpChannel.write(new DatagramPacket(snap.retainedDuplicate(), dest));
        }


        udpChannel.flush();

        snap.release();
    }


    /** @return {@code true} once a valid INPUT was stored */

    public boolean ingest(InetSocketAddress sender, ByteBuf content) {


        boolean release = true;

        try {

            final int INPUT_TAIL = 4 * 5 + 2;


            int need = UdpOpcodes.HEADER_BYTES + INPUT_TAIL;

            if (!content.isReadable(need)) {


                return false;
            }


            UdpHeader header = UdpHeader.read(content);

            if (header.matchIdUnsigned() != assignedMatchUnsignedLong) {


                return false;
            }


            int entityId = header.playerIdUnsigned();


            PlayerSimState ent = lookup(entityId);

            if (ent == null) {

                return false;
            }


            InetAddress expected = tcpGate[entityId];

            if (expected == null || !expected.equals(sender.getAddress())) {

                return false;
            }


            if (header.type() != UdpOpcodes.C_INPUT) {

                return false;
            }


            int seq = header.seqUnsigned();

            Integer previous = lastSeqByEntity.get(entityId);

            if (previous != null && Integer.compareUnsigned(seq, previous) <= 0) {

                return false;
            }

            lastSeqByEntity.put(entityId, seq);

            InetSocketAddress lock = udpBoundSlot[entityId];

            if (lock == null) {

                udpBoundSlot[entityId] = sender;
            } else if (!lock.equals(sender)) {

                return false;
            }

            /* predicted client position — ignored for authority */

            content.skipBytes(8);

            float vx = content.readFloat();

            float vy = content.readFloat();

            float aim = content.readFloat();

            int buttons = content.readUnsignedByte();

            int inSeq = content.readUnsignedByte();

            latestInputs.put(
                    entityId,
                    new ClientInputPayload(vx, vy, aim, buttons, inSeq));

            return true;
        }

        finally {

            if (release) {

                content.release();
            }
        }
    }

    private PlayerSimState lookup(int entityId) {

        if (entityId < 0 || entityId > maxEntityId) {
            return null;
        }

        return byEntityId[entityId];
    }

    private static void applyMotion(PlayerSimState ps, ClientInputPayload in) {

        float aim = ps.aimAngle;

        float vx = 0f;

        float vy = 0f;

        if (in != null) {

            aim = in.aimAngle();

            float rawVx = in.velX();

            float rawVy = in.velY();

            if (Math.hypot(rawVx, rawVy) <= 1e-4f && in.buttons() != 0) {

                float sp = MAX_SPEED * 0.85f;

                boolean up = bit(in.buttons(), 0);

                boolean down = bit(in.buttons(), 1);

                boolean left = bit(in.buttons(), 2);

                boolean right = bit(in.buttons(), 3);

                vx = ((right ? 1 : 0) - (left ? 1 : 0)) * sp;

                vy = -((up ? 1 : 0) - (down ? 1 : 0)) * sp;
            } else {

                float[] cap = capped(rawVx, rawVy);

                vx = cap[0];

                vy = cap[1];
            }
        }

        ps.integrate(TICK_DELTA, vx, vy, aim);
    }

    private static boolean bit(int mask, int bit) {

        return ((mask >>> bit) & 1) != 0;
    }

    private static float[] capped(float rawVx, float rawVy) {

        double mag = Math.hypot(rawVx, rawVy);

        if (mag <= MAX_SPEED || mag < 1e-6) {

            return new float[] { rawVx, rawVy };
        }

        float k = (float) (MAX_SPEED / mag);

        return new float[] { rawVx * k, rawVy * k };
    }
}
