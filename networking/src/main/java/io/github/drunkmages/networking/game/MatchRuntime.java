package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.github.drunkmages.networking.MatchEndPacket;
import io.github.drunkmages.networking.MatchStatEntry;

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
    public static final float[][] WALLS = {
            {-150, -20, -50, 20},    // Center-left barricade
            {50, -20, 150, 20},      // Center-right barricade
            {-20, -150, 20, -50},    // Bottom barricade
            {-20, 50, 20, 150},      // Top barricade
            {-300, -300, -200, -280},// Corner L-shapes
            {-300, -300, -280, -200},
            {200, 280, 300, 300},
            {280, 200, 300, 300},
            {-300, 280, -200, 300},
            {-300, 200, -280, 300},
            {200, -300, 300, -280},
            {280, -300, 300, -200}
    };
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

    private final int matchWireSignedInt;
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

    private final InetAddress[] tcpGate;
    private final InetSocketAddress[] udpBoundSlot;

    private final HashMap<Integer, Integer> lastSeqByEntity = new HashMap<>();
    private final HashMap<Integer, ClientInputPayload> latestInputs = new HashMap<>();

    private final ZoneManager zone = new ZoneManager();

    private static final class ServerProjectile {
        float x, y, vx, vy, life;
        int ownerId;
        float damage;
    }

    public interface DeathCallback {
        void onDeath(int victimId, int killerId, int placement);
    }

    private final ArrayList<ServerProjectile> serverProjectiles = new ArrayList<>();
    private final DeathCallback onDeath;
    private final java.util.function.Consumer<MatchEndPacket> onMatchEnd;
    private final java.util.Map<Integer, String> slotToNick;
    private boolean matchEnded = false;

    public static final class ServerItem {
        public int entityId;
        public int itemType;
        public float x, y;
    }
    private final ArrayList<ServerItem> groundItems = new ArrayList<>();
    private int nextItemEntityId = 1000;

    // ── Constructor ──────────────────────────────────────────────────────────

    public MatchRuntime(int matchWireSignedInt, List<MatchParticipant> rosterUnsorted, Map<Integer, String> slotToNick, DeathCallback onDeath, Consumer<MatchEndPacket> onMatchEnd) {
        this.onDeath = onDeath;
        this.onMatchEnd = onMatchEnd;
        this.slotToNick = slotToNick;
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

        this.matchWireSignedInt = matchWireSignedInt;
        this.assignedMatchUnsignedLong = Integer.toUnsignedLong(matchWireSignedInt);

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

        for (int i = 0; i < 30; i++) {
            ServerItem item = new ServerItem();
            item.entityId = nextItemEntityId++;

            // 2. Collision Fix: Prevent spawning inside walls
            float x, y;
            do {
                x = (float) ((Math.random() - 0.5) * (ARENA_HALF * 1.8));
                y = (float) ((Math.random() - 0.5) * (ARENA_HALF * 1.8));
            } while (isWallCollision(x, y, 8f));

            item.x = x;
            item.y = y;

            // 1. Rarity & Spatial Spawning: Correlate distance to center
            float dist = (float) Math.hypot(x, y);
            float normalizedDist = dist / ARENA_HALF;
            int rarity; // 0=Basic, 1=Uncommon, 2=Epic, 3=Legendary
            double roll = Math.random();

            if (normalizedDist < 0.3f) {         // Center (High chance of Gold)
                rarity = roll < 0.5 ? 3 : (roll < 0.8 ? 2 : 1);
            } else if (normalizedDist < 0.7f) {  // Mid
                rarity = roll < 0.15 ? 3 : (roll < 0.4 ? 2 : (roll < 0.8 ? 1 : 0));
            } else {                             // Outer Edges (Mostly White)
                rarity = roll < 0.05 ? 3 : (roll < 0.15 ? 2 : (roll < 0.35 ? 1 : 0));
            }

            int baseType = Math.random() > 0.5 ? 3 : 4; // 3=Shotgun, 4=AR
            item.itemType = (rarity << 8) | baseType;   // Encode rarity in upper bits
            groundItems.add(item);
        }

    }

    private static boolean isWallCollision(float x, float y, float radius) {
        for (float[] wall : WALLS) {
            if (x + radius > wall[0] && x - radius < wall[2] && y + radius > wall[1] && y - radius < wall[3]) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasLineOfSight(float x1, float y1, float x2, float y2) {
        for (float[] wall : WALLS) {
            if (lineIntersectsRect(x1, y1, x2, y2, wall[0], wall[1], wall[2], wall[3])) return false;
        }
        return true;
    }

    private static boolean lineIntersectsRect(float x1, float y1, float x2, float y2, float rx1, float ry1, float rx2, float ry2) {
        if (x1 >= rx1 && x1 <= rx2 && y1 >= ry1 && y1 <= ry2) return true;
        if (x2 >= rx1 && x2 <= rx2 && y2 >= ry1 && y2 <= ry2) return true;
        return lineIntersectsLine(x1, y1, x2, y2, rx1, ry1, rx2, ry1) ||
                lineIntersectsLine(x1, y1, x2, y2, rx2, ry1, rx2, ry2) ||
                lineIntersectsLine(x1, y1, x2, y2, rx2, ry2, rx1, ry2) ||
                lineIntersectsLine(x1, y1, x2, y2, rx1, ry2, rx1, ry1);
    }

    private static boolean lineIntersectsLine(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        float denom = ((y4 - y3) * (x2 - x1)) - ((x4 - x3) * (y2 - y1));
        if (denom == 0) return false;
        float ua = (((x4 - x3) * (y1 - y3)) - ((y4 - y3) * (x1 - x3))) / denom;
        float ub = (((x2 - x1) * (y1 - y3)) - ((y2 - y1) * (x1 - x3))) / denom;
        return (ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1);
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
        int aliveCount = 0;
        for (PlayerSimState ps : rosterOrdered) {
            if (ps.hp > 0) aliveCount++;
        }

        for (PlayerSimState ps : rosterOrdered) {
            if (ps.hp <= 0) continue;
            ps.fireCooldown -= TICK_DELTA;

            int currentWeaponType = ps.inventory[ps.selectedSlot] & 0xFF; // MASKED
            if (currentWeaponType == 0) continue; // Cannot shoot empty hands

            WeaponDef weapon = WeaponDef.get(currentWeaponType);

            if (ps.isShooting && ps.fireCooldown <= 0f) {
                ps.fireCooldown = weapon.fireRate();

                // FIX: Calculate custom rarity-based damage
                float projDamage = 15f;
                if (currentWeaponType == 3 || currentWeaponType == 4) { // Shotgun or AK
                    int rarity = (ps.inventory[ps.selectedSlot] >> 8) & 0xFF;
                    if (rarity == 3) projDamage = 25f;      // Gold
                    else if (rarity == 2) projDamage = 20f; // Purple
                    else projDamage = 15f;                  // Basic/White/Green
                } else if (currentWeaponType == 1) { // Pistol
                    projDamage = 17.5f;
                }

                for (int pCount = 0; pCount < weapon.projectiles(); pCount++) {
                    ServerProjectile p = new ServerProjectile();
                    p.x = ps.posX; p.y = ps.posY;
                    float angle = ps.aimAngle + (float)((Math.random() - 0.5) * weapon.spreadRadians());
                    p.vx = (float) Math.cos(angle) * weapon.bulletSpeed();
                    p.vy = (float) Math.sin(angle) * weapon.bulletSpeed();
                    p.life = 2.5f;
                    p.ownerId = ps.entityId;
                    p.damage = projDamage; // Apply calculated damage to the projectile
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
            boolean hitWall = false;


            for (float[] wall : WALLS) {
                if (p.x >= wall[0] && p.x <= wall[2] && p.y >= wall[1] && p.y <= wall[3]) {
                    hitWall = true;
                    break;
                }
            }
            if (hitWall) {
                serverProjectiles.remove(i);
                continue;
            }

            for (PlayerSimState target : rosterOrdered) {
                if (target.hp <= 0 || target.entityId == p.ownerId) continue;
                float dx = target.posX - p.x; float dy = target.posY - p.y;
                if (dx * dx + dy * dy < 14f * 14f) {
                    target.exactHp -= p.damage;
                    target.hp = (int) Math.ceil(target.exactHp);
                    hit = true;
                    PlayerSimState killer = lookup(p.ownerId);
                    if (killer != null && killer.entityId != target.entityId) killer.damageDealt += 25;

                    if (target.hp <= 0) {
                        target.placement = aliveCount;
                        target.survivalTicks = authoritativeTick;
                        if (killer != null && killer.entityId != target.entityId) killer.kills++;
                        onDeath.onDeath(target.entityId, p.ownerId, target.placement);
                        aliveCount--;
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
            float dx = ps.posX - zone.curX; float dy = ps.posY - zone.curY;
            if (dx * dx + dy * dy > zone.curRadius * zone.curRadius) {
                ps.exactHp -= zone.damagePerTick;
                if (ps.hp <= 0) {
                    ps.placement = aliveCount;
                    ps.survivalTicks = authoritativeTick;
                    onDeath.onDeath(ps.entityId, 0, ps.placement);
                    aliveCount--;
                }
            }
        }

        if (!matchEnded && (aliveCount <= 1 && rosterOrdered.length > 1 || rosterOrdered.length == 1 && aliveCount == 0)) {
            matchEnded = true;
            PlayerSimState winner = null;
            for (PlayerSimState ps : rosterOrdered) if (ps.hp > 0) winner = ps;

            int winnerId = winner != null ? winner.entityId : 0;
            String winnerNick = winner != null ? slotToNick.get(winnerId) : "";
            if (winner != null) {
                winner.placement = 1;
                winner.survivalTicks = authoritativeTick;
            }

            List<MatchStatEntry> stats = new ArrayList<>();
            for (PlayerSimState ps : rosterOrdered) {
                stats.add(new MatchStatEntry(ps.entityId, ps.placement, ps.kills, ps.damageDealt, ps.survivalTicks));
            }
            onMatchEnd.accept(new MatchEndPacket(assignedMatchUnsignedLong, winnerId, winnerNick, authoritativeTick, stats));
        }

        // 1. Apply client-driven motion
        for (PlayerSimState ps : rosterOrdered) {
            applyMotion(ps, latestInputs.get(ps.entityId));
        }

        // 2. Clamp each player to arena walls
        for (PlayerSimState ps : rosterOrdered) {
            clampToArena(ps);
            collideWithWalls(ps);
        }

        // 3. Resolve player-vs-player circle collisions (iterative, single pass)
        resolvePlayerCollisions();

        // 4. Re-clamp after pushback in case a collision pushed someone into a wall
        for (PlayerSimState ps : rosterOrdered) {
            clampToArena(ps);
            collideWithWalls(ps);
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

    private static void collideWithWalls(PlayerSimState ps) {
        for (float[] wall : WALLS) {
            float testX = ps.posX;
            float testY = ps.posY;

            // Find closest edge
            if (ps.posX < wall[0]) testX = wall[0];
            else if (ps.posX > wall[2]) testX = wall[2];

            if (ps.posY < wall[1]) testY = wall[1];
            else if (ps.posY > wall[3]) testY = wall[3];

            float distX = ps.posX - testX;
            float distY = ps.posY - testY;
            float distance = (float) Math.sqrt((distX * distX) + (distY * distY));

            if (distance < PLAYER_RADIUS) {
                float overlap = PLAYER_RADIUS - distance;
                if (distance == 0) {
                    ps.posY += PLAYER_RADIUS;
                    ps.velY = 0;
                } else {
                    float nx = distX / distance;
                    float ny = distY / distance;
                    ps.posX += nx * overlap;
                    ps.posY += ny * overlap;

                    // Cancel velocity in collision direction
                    float dot = ps.velX * nx + ps.velY * ny;
                    if (dot < 0f) {
                        ps.velX -= dot * nx;
                        ps.velY -= dot * ny;
                    }
                }
            }
        }
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
//                if (ent.hp <= 0) {
//                    ent.hp = ent.maxHp;
//                    // Respawn at a random location near the center
//                    ent.posX = (float) ((Math.random() - 0.5) * 200f);
//                    ent.posY = (float) ((Math.random() - 0.5) * 200f);
//                    ent.velX = 0f;
//                    ent.velY = 0f;
//                }
                return true;
            }

            if (header.type() == UdpOpcodes.C_ITEM_PICKUP) {
                if (ent.hp <= 0) return true;

                // Use the most recent aim angle from client input
                float currentAim = ent.aimAngle;
                ClientInputPayload in = latestInputs.get(ent.entityId);
                if (in != null) currentAim = in.aimAngle();

                int bestIndex = -1;
                float bestAngleDiff = 0.45f; // Server gives a slight lag tolerance (~25 degrees)

                for (int i = 0; i < groundItems.size(); i++) {
                    ServerItem item = groundItems.get(i);
                    float dx = item.x - ent.posX;
                    float dy = item.y - ent.posY;
                    float distSq = dx * dx + dy * dy;

                    if (distSq < 60f * 60f) {
                        // 3. Line of Sight Check (Server Authority)
                        if (!hasLineOfSight(ent.posX, ent.posY, item.x, item.y)) continue;

                        float diff = 0f;
                        // If the player is standing directly on top of the item, ignore the exact aim requirement
                        if (distSq > 14f * 14f) {
                            float angleToItem = (float) Math.atan2(dy, dx);
                            diff = Math.abs(angleToItem - currentAim);
                            while (diff > Math.PI) diff -= (float) (Math.PI * 2);
                            while (diff < -Math.PI) diff += (float) (Math.PI * 2);
                            diff = Math.abs(diff);
                        }

                        if (diff < bestAngleDiff) {
                            bestAngleDiff = diff;
                            bestIndex = i;
                        }
                    }
                }

                // Pick up the most accurately aimed item
                if (bestIndex != -1) {
                    ServerItem item = groundItems.get(bestIndex);
                    boolean pickedUp = false;
                    for (int slot = 0; slot < 5; slot++) {
                        if (ent.inventory[slot] == 0) {
                            ent.inventory[slot] = item.itemType;
                            pickedUp = true;
                            break;
                        }
                    }
                    if (!pickedUp) {
                        if ((ent.inventory[ent.selectedSlot] & 0xFF) != 1) { // MASKED check
                            ServerItem drop = new ServerItem();
                            drop.entityId = nextItemEntityId++;
                            drop.itemType = ent.inventory[ent.selectedSlot];
                            drop.x = ent.posX; drop.y = ent.posY;
                            groundItems.add(drop);
                        }
                        ent.inventory[ent.selectedSlot] = item.itemType;
                    }
                    groundItems.remove(bestIndex);
                }
                return true;
            }
            if (header.type() == UdpOpcodes.C_ITEM_USE) {
                if (ent.hp <= 0) return true;
                if (content.isReadable(1)) {
                    int slot = content.readUnsignedByte();
                    if (slot >= 0 && slot < 5) ent.selectedSlot = slot;
                }
                return true;
            }

            if (header.type() == UdpOpcodes.C_ITEM_DROP) {
                if (ent.hp <= 0) return true;
                if (content.isReadable(2)) {
                    int slot = content.readUnsignedByte();
                    int qty = content.readUnsignedByte();
                    if (slot >= 0 && slot < 5) {
                        int itemType = ent.inventory[slot];
                        int baseType = itemType & 0xFF;

                        // FIX: Allow pistol to drop, only prevent dropping empty hands (0)
                        if (baseType != 0) {
                            ServerItem drop = new ServerItem();
                            drop.entityId = nextItemEntityId++;
                            drop.itemType = itemType;
                            drop.x = ent.posX;
                            drop.y = ent.posY;
                            groundItems.add(drop);
                            ent.inventory[slot] = 0; // Clear the slot
                        }
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