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
import java.util.ArrayList;
import java.util.function.BiConsumer;

/**
 * Authoritative simulation for one match instance.
 *
 * <p>Intended to be driven only from the {@link Channel#eventLoop()} that
 * owns the UDP transport (single-threaded execution — no locks needed).
 *
 * <h3>Player physics</h3>
 * <ol>
 *   <li>Apply client-driven motion ({@link #applyMotion}).</li>
 *   <li>Clamp each player to the arena boundary ({@link #clampToArena}).</li>
 *   <li>Resolve player-vs-player circle collisions ({@link #resolvePlayerCollisions}).</li>
 *   <li>Re-clamp after collision resolution to prevent wall-escape.</li>
 * </ol>
 */
public final class MatchRuntime {

    // ── Physics constants ────────────────────────────────────────────────────

    /** §10: provisional top speed cap. */
    static final float MAX_SPEED = 42f;

    private static final float TICK_DELTA = 0.05f;

    /**
     * Physical radius used for both wall clamping and player-vs-player
     * circle collision.  Must match the visual radius rendered by the client.
     */
    private static final float PLAYER_RADIUS = 7f;

    /**
     * Minimum distance between two player centres before pushback triggers.
     * Equals the sum of their radii (both are the same size).
     */
    private static final float PLAYER_MIN_DIST = PLAYER_RADIUS * 2f;

    /**
     * Inner playable half-extent of the arena.
     * Walls are 4 units thick inside the ±140 boundary, so the inner edge
     * of each wall sits at ±136.  Subtracting the player radius gives the
     * furthest centre position that keeps the circle fully inside.
     */
    public static final float ARENA_HALF = 400f;
    private static final float ARENA_BOUND = (ARENA_HALF - 4f) - PLAYER_RADIUS; // = 129

    // ── Match identity ───────────────────────────────────────────────────────

    private final int  matchWireSignedInt;
    private final long assignedMatchUnsignedLong;

    // ── Simulation state ─────────────────────────────────────────────────────

    private volatile int authoritativeTick;

    /** Monotonic server-authored UDP sequence numbers for downlink frames. */
    private int outboundSeqCounter;

    private final int maxEntityId;

    /** Dense iteration order mirroring MATCH roster order. */
    private final PlayerSimState[] rosterOrdered;

    /** Random access indexed by entity id. */
    private final PlayerSimState[] byEntityId;

    private final InetAddress[]       tcpGate;
    private final InetSocketAddress[] udpBoundSlot;

    private final HashMap<Integer, Integer>          lastSeqByEntity = new HashMap<>();
    private final HashMap<Integer, ClientInputPayload> latestInputs  = new HashMap<>();

    private final ZoneManager zone = new ZoneManager();

    private static final class ServerProjectile {
        float x, y, vx, vy, life;
        int ownerId;
    }
    private final ArrayList<ServerProjectile> serverProjectiles = new ArrayList<>();
    private final BiConsumer<Integer, Integer> onDeath;

    public static final class ServerItem {
        public int entityId;
        public int itemType;
        public float x, y;
    }
    private final ArrayList<ServerItem> groundItems = new ArrayList<>();
    private int nextItemEntityId = 1000;

    // ── Constructor ──────────────────────────────────────────────────────────

    public MatchRuntime(int matchWireSignedInt, List<MatchParticipant> rosterUnsorted, BiConsumer<Integer, Integer> onDeath) {
        this.onDeath = onDeath;
        Objects.requireNonNull(rosterUnsorted);

        List<MatchParticipant> roster =
                rosterUnsorted.stream()
                        .sorted(Comparator.comparingInt(MatchParticipant::matchLocalPlayerId))
                        .toList();

        if (roster.isEmpty()) throw new IllegalArgumentException("empty roster");

        maxEntityId = roster.stream()
                .mapToInt(MatchParticipant::matchLocalPlayerId)
                .max()
                .orElseThrow();

        this.matchWireSignedInt       = matchWireSignedInt;
        this.assignedMatchUnsignedLong = Integer.toUnsignedLong(matchWireSignedInt);

        byEntityId   = new PlayerSimState[maxEntityId + 1];
        rosterOrdered = new PlayerSimState[roster.size()];
        tcpGate      = new InetAddress[maxEntityId + 1];
        udpBoundSlot = new InetSocketAddress[maxEntityId + 1];

        int ox = 0;
        for (MatchParticipant mp : roster) {
            int eid   = mp.matchLocalPlayerId();
            InetAddress ipa = Objects.requireNonNull(mp.tcpRemoteIp(), "TCP remote IP");
            PlayerSimState st = new PlayerSimState(eid, mp.spawnX(), mp.spawnY());
            rosterOrdered[ox++] = st;
            byEntityId[eid]     = st;
            tcpGate[eid]        = ipa;
            udpBoundSlot[eid]   = null;
        }

        for (int i = 0; i < 30; i++) {
            ServerItem item = new ServerItem();
            item.entityId = nextItemEntityId++;
            item.itemType = Math.random() > 0.5 ? 3 : 4; // 50/50 Shotgun or AR
            item.x = (float) ((Math.random() - 0.5) * (ARENA_HALF * 1.5));
            item.y = (float) ((Math.random() - 0.5) * (ARENA_HALF * 1.5));
            groundItems.add(item);
        }

    }

    public int signedMatchBits() {
        return matchWireSignedInt;
    }

    // ── Tick loop ─────────────────────────────────────────────────────────────

    /**
     * Advances the simulation by one tick, resolves physics, then multicasts
     * a {@code WORLD_SNAPSHOT} to every bound UDP endpoint.
     *
     * <p>Must be called exclusively from the UDP channel's event loop.
     */
    public void advanceAndMulticast(Channel udpChannel) {
        for (PlayerSimState ps : rosterOrdered) {
            if (ps.hp <= 0) continue;
            ps.fireCooldown -= TICK_DELTA;

            WeaponDef weapon = WeaponDef.get(ps.heldWeaponType);

            if (ps.isShooting && ps.fireCooldown <= 0f) {
                ps.fireCooldown = weapon.fireRate();
                // Loop based on projectiles (Shotguns fire 5!)
                for (int pCount = 0; pCount < weapon.projectiles(); pCount++) {
                    ServerProjectile p = new ServerProjectile();
                    p.x = ps.posX; p.y = ps.posY;
                    float angle = ps.aimAngle + (float)((Math.random() - 0.5) * weapon.spreadRadians());
                    p.vx = (float) Math.cos(angle) * weapon.bulletSpeed();
                    p.vy = (float) Math.sin(angle) * weapon.bulletSpeed();
                    p.life = 2.5f;
                    p.ownerId = ps.entityId;
                    serverProjectiles.add(p);
                }
            }
        }

        // Projectile Step & Hit Detection
        for (int i = serverProjectiles.size() - 1; i >= 0; i--) {
            ServerProjectile p = serverProjectiles.get(i);
            p.x += p.vx * TICK_DELTA;
            p.y += p.vy * TICK_DELTA;
            p.life -= TICK_DELTA;
            boolean hit = false;

            for (PlayerSimState target : rosterOrdered) {
                if (target.hp <= 0 || target.entityId == p.ownerId) continue;
                float dx = target.posX - p.x;
                float dy = target.posY - p.y;
                // Hitbox check (using 14f to make it feel generous/fair)
                if (dx * dx + dy * dy < 14f * 14f) {
                    target.hp -= 25; // 4 hits to kill
                    hit = true;
                    if (target.hp <= 0) {
                        onDeath.accept(target.entityId, p.ownerId); // Notify death
                    }
                    break;
                }
            }
            if (hit || p.life <= 0f || Math.abs(p.x) > ARENA_HALF || Math.abs(p.y) > ARENA_HALF) {
                serverProjectiles.remove(i);
            }
        }

        authoritativeTick++;

        zone.tick();
        for (PlayerSimState ps : rosterOrdered) {
            if (ps.hp <= 0) continue;
            float dx = ps.posX - zone.curX;
            float dy = ps.posY - zone.curY;
            if (dx * dx + dy * dy > zone.curRadius * zone.curRadius) {
                ps.hp -= zone.damagePerTick;
                if (ps.hp <= 0) onDeath.accept(ps.entityId, 0); // 0 = Environment/Storm
            }
        }

        // 1. Apply client-driven motion
        for (PlayerSimState ps : rosterOrdered) {
            applyMotion(ps, latestInputs.get(ps.entityId));
        }

        // 2. Clamp each player to arena walls
        for (PlayerSimState ps : rosterOrdered) {
            clampToArena(ps);
        }

        // 3. Resolve player-vs-player circle collisions (iterative, single pass)
        resolvePlayerCollisions();

        // 4. Re-clamp after pushback in case a collision pushed someone into a wall
        for (PlayerSimState ps : rosterOrdered) {
            clampToArena(ps);
        }

        // 5. Build and multicast WORLD_SNAPSHOT
        outboundSeqCounter++;
        ByteBuf snap = WorldSnapshotCodec.encode(
                udpChannel.alloc(),
                outboundSeqCounter,
                authoritativeTick,
                signedMatchBits(),
                rosterOrdered,
                groundItems);

        for (int eid = 1; eid <= maxEntityId; eid++) {
            InetSocketAddress dest = udpBoundSlot[eid];
            if (dest == null) continue;
            udpChannel.write(new DatagramPacket(snap.retainedDuplicate(), dest));
        }

        if (authoritativeTick % 20 == 0) {
            ByteBuf zoneSnap = zone.encode(udpChannel.alloc(), outboundSeqCounter, matchWireSignedInt);
            for (int eid = 1; eid <= maxEntityId; eid++) {
                InetSocketAddress dest = udpBoundSlot[eid];
                if (dest != null) udpChannel.write(new DatagramPacket(zoneSnap.retainedDuplicate(), dest));
            }
            zoneSnap.release();
        }

        udpChannel.flush();
        snap.release();
    }

    // ── Ingress (client → server) ─────────────────────────────────────────────

    /** @return {@code true} once a valid INPUT was stored */
    public boolean ingest(InetSocketAddress sender, ByteBuf content) {
        boolean release = true;
        try {
            if (!content.isReadable(UdpOpcodes.HEADER_BYTES)) return false;

            UdpHeader header = UdpHeader.read(content);
            if (header.matchIdUnsigned() != assignedMatchUnsignedLong) return false;

            int entityId = header.playerIdUnsigned();
            PlayerSimState ent = lookup(entityId);
            if (ent == null) return false;

            InetAddress expected = tcpGate[entityId];
            if (expected == null || !expected.equals(sender.getAddress())) return false;

            if (header.type() == UdpOpcodes.C_RELOAD) {
                if (ent.hp <= 0) {
                    ent.hp = ent.maxHp;
                    // Respawn at a random location near the center
                    ent.posX = (float) ((Math.random() - 0.5) * 200f);
                    ent.posY = (float) ((Math.random() - 0.5) * 200f);
                    ent.velX = 0f;
                    ent.velY = 0f;
                }
                return true;
            }

            if (header.type() == UdpOpcodes.C_ITEM_PICKUP) {
                if (ent.hp <= 0) return true;
                for (int i = 0; i < groundItems.size(); i++) {
                    ServerItem item = groundItems.get(i);
                    float dx = item.x - ent.posX;
                    float dy = item.y - ent.posY;
                    if (dx * dx + dy * dy < 25f * 25f) { // 25 units pickup radius
                        // Drop current weapon if it isn't the starter pistol
                        if (ent.heldWeaponType != 1) {
                            ServerItem drop = new ServerItem();
                            drop.entityId = nextItemEntityId++;
                            drop.itemType = ent.heldWeaponType;
                            drop.x = ent.posX; drop.y = ent.posY;
                            groundItems.add(drop);
                        }
                        // Equip new weapon and remove from ground
                        ent.heldWeaponType = item.itemType;
                        groundItems.remove(i);
                        break;
                    }
                }
                return true;
            }

            final int INPUT_TAIL = 4 * 5 + 2;
            if (!content.isReadable(INPUT_TAIL)) return false;

            int seq      = header.seqUnsigned();
            Integer prev = lastSeqByEntity.get(entityId);
            if (prev != null && Integer.compareUnsigned(seq, prev) <= 0) return false;
            lastSeqByEntity.put(entityId, seq);

            InetSocketAddress lock = udpBoundSlot[entityId];
            if (lock == null) {
                udpBoundSlot[entityId] = sender;
            } else if (!lock.equals(sender)) {
                return false;
            }

            // predicted client position — ignored for authority
            content.skipBytes(8);
            float vx      = content.readFloat();
            float vy      = content.readFloat();
            float aim     = content.readFloat();
            int   btns    = content.readUnsignedByte();
            int   inSeq   = content.readUnsignedByte();

            latestInputs.put(entityId, new ClientInputPayload(vx, vy, aim, btns, inSeq));
            return true;
        } finally {
            if (release) content.release();
        }
    }

    // ── Physics helpers ───────────────────────────────────────────────────────

    /**
     * Integrates one player's position from the latest client input.
     * The server trusts velocity magnitude (capped) and button bits;
     * it ignores client-predicted position ({@code pos_x}/{@code pos_y}).
     */
    private static void applyMotion(PlayerSimState ps, ClientInputPayload in) {
        if (ps.hp <= 0) {
            ps.velX = 0f;
            ps.velY = 0f;
            ps.isShooting = false;
            return;
        }

        float aim = ps.aimAngle;
        float vx  = 0f;
        float vy  = 0f;

        if (in != null) {
            aim = in.aimAngle();
            ps.isShooting = (in.buttons() & 128) != 0;
            float rawVx = in.velX();
            float rawVy = in.velY();

            if (Math.hypot(rawVx, rawVy) <= 1e-4f && in.buttons() != 0) {
                // Client sent no velocity — derive from button bitmask
                float sp     = MAX_SPEED * 0.85f;
                boolean up    = bit(in.buttons(), 0);
                boolean down  = bit(in.buttons(), 1);
                boolean left  = bit(in.buttons(), 2);
                boolean right = bit(in.buttons(), 3);
                vx =  ((right ? 1 : 0) - (left ? 1 : 0)) * sp;
                vy = -((up    ? 1 : 0) - (down ? 1 : 0)) * sp;
            } else {
                float[] cap = capped(rawVx, rawVy);
                vx = cap[0];
                vy = cap[1];
            }
        }

        ps.integrate(TICK_DELTA, vx, vy, aim);
    }

    /**
     * Clamps a player's centre position so they cannot pass through the
     * arena walls.  The inner edge of each wall is at ±(ARENA_HALF − 4),
     * so the player centre must stay within ±ARENA_BOUND.
     */
    private static void clampToArena(PlayerSimState ps) {
        if (ps.posX < -ARENA_BOUND) { ps.posX = -ARENA_BOUND; ps.velX = 0f; }
        if (ps.posX >  ARENA_BOUND) { ps.posX =  ARENA_BOUND; ps.velX = 0f; }
        if (ps.posY < -ARENA_BOUND) { ps.posY = -ARENA_BOUND; ps.velY = 0f; }
        if (ps.posY >  ARENA_BOUND) { ps.posY =  ARENA_BOUND; ps.velY = 0f; }
    }

    /**
     * Single-pass O(n²) circle-circle pushback.
     *
     * <p>When two player circles overlap, each is pushed halfway out of the
     * other along the centre-to-centre axis.  This is not perfectly stable
     * under heavy pile-ups (would need iteration), but is correct and cheap
     * for the expected 2–20 player count.
     */
    private void resolvePlayerCollisions() {
        int n = rosterOrdered.length;
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                PlayerSimState a = rosterOrdered[i];
                PlayerSimState b = rosterOrdered[j];

                // fix, don't check collisions after player death (one of :))
                if (a.hp <= 0 || b.hp <= 0) continue;

                float dx     = b.posX - a.posX;
                float dy     = b.posY - a.posY;
                float distSq = dx * dx + dy * dy;

                if (distSq >= PLAYER_MIN_DIST * PLAYER_MIN_DIST || distSq < 1e-6f) {
                    continue; // no overlap, or coincident (avoid div-by-zero)
                }

                float dist    = (float) Math.sqrt(distSq);
                float overlap = PLAYER_MIN_DIST - dist;

                // Unit normal from a → b
                float nx = dx / dist;
                float ny = dy / dist;

                // Push each player half the overlap distance
                float half = overlap * 0.5f;
                a.posX -= nx * half;
                a.posY -= ny * half;
                b.posX += nx * half;
                b.posY += ny * half;

                // Cancel velocity components pushing into the collision
                // (prevents tunnelling through at high speed)
                float relVx = b.velX - a.velX;
                float relVy = b.velY - a.velY;
                float dot   = relVx * nx + relVy * ny;
                if (dot < 0f) {
                    // Project out the approaching component (elastic-ish, mass = 1 each)
                    a.velX -= nx * (-dot * 0.5f);
                    a.velY -= ny * (-dot * 0.5f);
                    b.velX += nx * (-dot * 0.5f);
                    b.velY += ny * (-dot * 0.5f);
                }
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private PlayerSimState lookup(int entityId) {
        if (entityId < 0 || entityId > maxEntityId) return null;
        return byEntityId[entityId];
    }

    private static boolean bit(int mask, int bit) {
        return ((mask >>> bit) & 1) != 0;
    }

    private static float[] capped(float rawVx, float rawVy) {
        double mag = Math.hypot(rawVx, rawVy);
        if (mag <= MAX_SPEED || mag < 1e-6) return new float[]{ rawVx, rawVy };
        float k = (float) (MAX_SPEED / mag);
        return new float[]{ rawVx * k, rawVy * k };
    }
    public void disconnectPlayer(int entityId) {
        PlayerSimState ps = lookup(entityId);
        if (ps != null) {
            ps.hp = 0;             // Kill the player
            ps.isShooting = false; // Force stop shooting
            latestInputs.remove(entityId); // Clear their sticky inputs
            udpBoundSlot[entityId] = null; // Free their UDP slot
        }
    }

}