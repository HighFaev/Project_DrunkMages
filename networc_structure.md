# Java Royale — Network Protocol Design

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Transport Layer Decisions](#2-transport-layer-decisions)
3. [Wire Format Conventions](#3-wire-format-conventions)
4. [TCP — Lobby Layer](#4-tcp--lobby-layer)
    - [Packet IDs](#41-packet-ids)
    - [Client → Server packets](#42-client--server-packets)
    - [Server → Client packets](#43-server--client-packets)
5. [UDP — Game Layer](#5-udp--game-layer)
    - [UDP Header](#51-udp-header)
    - [Packet IDs](#52-packet-ids)
    - [Client → Server packets](#53-client--server-packets)
    - [Server → Client packets](#54-server--client-packets)
    - [Entity state encoding](#55-entity-state-encoding)
6. [Full Connection Flow](#6-full-connection-flow)
7. [Gameplay State Machine](#7-gameplay-state-machine)
8. [Client-Side Prediction & Server Reconciliation](#8-client-side-prediction--server-reconciliation)
9. [Zone / Storm Mechanics](#9-zone--storm-mechanics)
10. [Server Validation Rules](#10-server-validation-rules)
11. [Sequence Numbers & Packet Loss](#11-sequence-numbers--packet-loss)
12. [Future Considerations](#12-future-considerations)

---

## 1. Architecture Overview

```
  CLIENT                          SERVER
  ──────                          ──────
  App (libGDX)
  │
  ├── NetworkClient (TCP) ──────► LobbyServer (TCP :25565)
  │     Handshake, roster,              Authoritative lobby state
  │     match lifecycle                 Matchmaking
  │
  └── GameClient (UDP) ─────────► GameServer (UDP :25566)
        Input, actions                  Tick loop (~20 Hz)
        ◄────────────────────────       World snapshots, hits,
                                        zone, item events
```

Each match runs as a self-contained **tick loop** on the game server.
The lobby server and game server can be the same process on different ports,
or separate processes for scalability.

**Players per match:** 20–50  
**Game world:** top-down 2D  
**Tick rate:** 20 Hz (50 ms / tick) — adjustable  
**Snapshot send rate:** every tick (20 Hz) using delta compression

---

## 2. Transport Layer Decisions

| Concern | Protocol | Reason |
|---|---|---|
| Login, handshake, roster | TCP | Must arrive once, in order. Small volume. |
| Matchmaking, match start/end | TCP | Lifecycle events must not be missed. |
| Player input | UDP | High frequency (20–60 Hz), old inputs are worthless. |
| World snapshots | UDP | High frequency, stale snapshots are discarded by sequence number. |
| Hit confirmation | UDP | Low latency feedback; server re-sends on miss via TCP fallback if needed. |
| Item pickup result | UDP | Same as hit; tolerate one lost packet. |
| Player death | **TCP** | Must arrive exactly once — drives UI and eliminates the player. |
| Match end / results | **TCP** | Must arrive exactly once. |

> **Rule of thumb:** if losing the packet means the player is in a permanently
> broken state, use TCP. If losing it just means a slightly stale frame, use UDP.

---

## 3. Wire Format Conventions

### Primitive types

| Symbol | Size | Description |
|---|---|---|
| `u8` | 1 byte | Unsigned byte (packet type, flags, counts ≤ 255) |
| `u16` | 2 bytes | Unsigned short, big-endian |
| `u32` | 4 bytes | Unsigned int, big-endian |
| `i16` | 2 bytes | Signed short, big-endian |
| `i32` | 4 bytes | Signed int, big-endian |
| `f32` | 4 bytes | IEEE 754 float, big-endian |
| `bool` | 1 byte | 0x00 = false, 0x01 = true |
| `str` | `u16` + N bytes | UTF-8 string: length prefix then bytes |

### Coordinate system

World coordinates are `f32` values in **world units** (1 unit = 1 tile/pixel,
defined by the game map). The origin (0, 0) is the map centre.
Angles are `f32` **radians**, measured clockwise from the positive X axis.

### Entity and item IDs

| ID type | Type | Range | Notes |
|---|---|---|---|
| Player ID | `u16` | 1–65535 | Assigned at handshake |
| Entity ID | `u16` | 1–65535 | Unique per match; reused after death |
| Item instance ID | `u32` | 1–2³²-1 | Unique per match; never reused |
| Item type | `u16` | See §5.5 | Defines appearance and behaviour |

---

## 4. TCP — Lobby Layer

The existing Netty pipeline is unchanged. Every TCP packet starts with a
**1-byte type ID**; the payload immediately follows with no length prefix
(each packet type has a fixed or self-delimiting layout).

### 4.1 Packet IDs

| Direction | ID | Name |
|---|---|---|
| C → S | `0x01` | `HANDSHAKE` |
| C → S | `0x02` | `PLAYER_READY` |
| C → S | `0x03` | `PLAYER_LEAVE_LOBBY` |
| S → C | `0x81` | `WELCOME` |
| S → C | `0x82` | `ROSTER_UPDATE` |
| S → C | `0x83` | `MATCH_FOUND` |
| S → C | `0x84` | `MATCH_COUNTDOWN` |
| S → C | `0x85` | `MATCH_START` |
| S → C | `0x86` | `PLAYER_DIED_TCP` |
| S → C | `0x87` | `MATCH_END` |
| S → C | `0x88` | `KICK` |

### 4.2 Client → Server Packets

---

#### `0x01` HANDSHAKE *(already implemented)*
Sent immediately after TCP connect.

```
u8   type        = 0x01
str  nickname
```

---

#### `0x02` PLAYER_READY
Client signals it has finished loading and is ready to enter the match.

```
u8   type        = 0x02
```

---

#### `0x03` PLAYER_LEAVE_LOBBY
Graceful disconnect from the lobby (before a match starts).

```
u8   type        = 0x03
```

---

### 4.3 Server → Client Packets

---

#### `0x81` WELCOME *(already implemented)*
Sent immediately after a valid HANDSHAKE.

```
u8   type        = 0x81
i32  player_id       -- assigned permanent ID for this session
```

---

#### `0x82` ROSTER_UPDATE *(already implemented)*
Broadcast to all lobby clients on join or leave.

```
u8   type        = 0x82
i32  count
     --- repeated count times ---
     i32  player_id
     str  nickname
```

---

#### `0x83` MATCH_FOUND
Sent when the server has assembled a match (enough players, or forced start).
Tells the client where to direct UDP traffic and what match it is joining.

```
u8   type            = 0x83
u32  match_id            -- unique match identifier
str  udp_host            -- UDP game server host (often same IP)
u16  udp_port            -- UDP game server port (default 25566)
u16  local_player_id     -- the player's ID within this match
u8   player_count        -- total players in this match
f32  spawn_x             -- initial spawn position
f32  spawn_y
u8   countdown_seconds   -- how long until MATCH_START
```

---

#### `0x84` MATCH_COUNTDOWN
Periodic tick during the pre-game countdown (sent every second).

```
u8   type            = 0x84
u8   seconds_left
```

---

#### `0x85` MATCH_START
Countdown reached zero; gameplay is now live. Client should start
sending UDP INPUT packets and begin rendering the game world.

```
u8   type            = 0x85
u32  start_tick          -- authoritative tick 0 for this match
u32  server_time_ms      -- server wall-clock ms at start (for ping calc)
```

---

#### `0x86` PLAYER_DIED_TCP
Sent over TCP to guarantee delivery. Eliminates the player from the match.
Mirrors `0x83` UDP packet but guaranteed; client deduplicates by player_id + tick.

```
u8   type            = 0x86
u16  player_id           -- who died
u16  killer_id           -- 0 = zone damage / fall damage / suicide
str  killer_nickname     -- empty string if no killer
u8   placement           -- e.g. 15 = died in 15th place
```

---

#### `0x87` MATCH_END
Sent when only one player (or no players) remains alive.

```
u8   type            = 0x87
u32  match_id
u16  winner_id           -- 0 = no winner (all died simultaneously)
str  winner_nickname
u32  duration_ticks      -- total match length in ticks
     --- per-player stats: repeated player_count times ---
u8   player_count
     u16  player_id
     u8   placement
     u16  kills
     u16  damage_dealt
     u32  survival_ticks
```

---

#### `0x88` KICK
Server forcibly removes a client (cheat detection, timeout, server shutdown).

```
u8   type            = 0x88
str  reason
```

---

## 5. UDP — Game Layer

UDP packets are sent between the client's game port and the server's game port
(default `25566`). The client must authenticate each UDP packet with its
`player_id` and `match_id` so the server can reject spoofed packets.

### 5.1 UDP Header

Every UDP packet — in both directions — begins with this fixed 11-byte header:

```
u8   type                -- packet type (see §5.2)
u32  seq                 -- sender's sequence number, monotonically increasing
u32  tick                -- game tick this packet refers to
u16  player_id           -- sender's player ID (client→server) or 0 (server→client)
```

The server ignores any UDP packet whose `player_id` does not match the
authenticated session for that source IP:port, or whose `match_id` is wrong.

---

### 5.2 Packet IDs

| Direction | ID | Name | Rate |
|---|---|---|---|
| C → S | `0x01` | `INPUT` | Every client tick (~20–60 Hz) |
| C → S | `0x02` | `SHOOT` | On fire button press |
| C → S | `0x03` | `ITEM_PICKUP` | On interact press near item |
| C → S | `0x04` | `ITEM_USE` | On use button press |
| C → S | `0x05` | `ITEM_DROP` | On drop button press |
| C → S | `0x06` | `RELOAD` | On reload button press |
| C → S | `0x07` | `PING` | Every ~1 s |
| S → C | `0x81` | `WORLD_SNAPSHOT` | Every server tick (20 Hz) |
| S → C | `0x82` | `HIT_CONFIRM` | On hit |
| S → C | `0x83` | `PLAYER_DIED_UDP` | On death (best-effort; TCP is authoritative) |
| S → C | `0x84` | `ZONE_STATE` | On zone change + every 5 s |
| S → C | `0x85` | `ITEM_SPAWNED` | When item appears |
| S → C | `0x86` | `ITEM_DESPAWNED` | When item disappears |
| S → C | `0x87` | `ITEM_PICKUP_RESULT` | After pickup attempt |
| S → C | `0x88` | `PROJECTILE_SPAWN` | When a bullet/projectile is created |
| S → C | `0x89` | `PONG` | Reply to PING |

---

### 5.3 Client → Server Packets

All packets include the common UDP header (§5.1) before the payload below.

---

#### `0x01` INPUT
The primary input packet. Sent every client tick regardless of whether
anything changed — the server needs a heartbeat to detect timeout.

```
[header]
f32  pos_x          -- client-predicted position
f32  pos_y
f32  vel_x          -- client-predicted velocity
f32  vel_y
f32  aim_angle      -- radians, direction the player is facing/aiming
u8   buttons        -- bitmask (see below)
u8   input_seq      -- wrapping 0–255 input sequence; used for reconciliation
```

**Buttons bitmask (`u8`):**

| Bit | Meaning |
|---|---|
| 0 | Move up |
| 1 | Move down |
| 2 | Move left |
| 3 | Move right |
| 4 | Sprint |
| 5 | Crouch / prone |
| 6 | Interact |
| 7 | *(reserved)* |

> The server re-simulates movement from the last authoritative state using
> the received velocity + buttons; it does **not** trust `pos_x`/`pos_y`
> directly. They are included to measure prediction error for correction.

---

#### `0x02` SHOOT

```
[header]
u8   weapon_slot    -- 0 = primary, 1 = secondary, 2 = melee
f32  aim_angle      -- angle at time of fire (may differ from INPUT aim)
f32  origin_x       -- muzzle position (client-side, for lag compensation hint)
f32  origin_y
u8   burst_index    -- 0 for single fire; 0,1,2 for burst weapons
```

The server validates the shoot: checks weapon is equipped, has ammo, cooldown
has elapsed, and the player is alive. If valid, it creates a server-side
projectile and broadcasts `PROJECTILE_SPAWN` + resolves hit detection.

---

#### `0x03` ITEM_PICKUP

```
[header]
u32  item_instance_id  -- which item to pick up
```

Server validates proximity (player must be within `pickup_radius` of the item)
and inventory space. Responds with `ITEM_PICKUP_RESULT`.

---

#### `0x04` ITEM_USE

```
[header]
u8   inventory_slot    -- 0–7 (see inventory layout §5.5)
```

Examples: use a medkit (heals over time), throw a grenade.

---

#### `0x05` ITEM_DROP

```
[header]
u8   inventory_slot
u8   quantity          -- for stackable items (ammo); 0 = drop all
```

---

#### `0x06` RELOAD

```
[header]
u8   weapon_slot       -- 0 = primary, 1 = secondary
```

---

#### `0x07` PING

```
[header]
u32  client_time_ms    -- client's local clock in milliseconds
```

---

### 5.4 Server → Client Packets

---

#### `0x81` WORLD_SNAPSHOT
The core snapshot packet. Sent every server tick to every connected client.
Contains the authoritative state of every entity visible to this client
(full snapshot or delta — see §11).

```
[header]
u8   flags             -- bit 0: full snapshot (1) vs delta (0)
u8   entity_count
     --- repeated entity_count times ---
     [entity_state]    -- see §5.5
```

Clients discard any snapshot with a `seq` lower than the last processed one
(handles out-of-order UDP delivery).

---

#### `0x82` HIT_CONFIRM
Server confirms that a shot from this client hit a target. Used to show
hit markers / damage numbers client-side.

```
[header]
u16  target_id         -- entity that was hit
u16  damage            -- HP removed
u16  target_hp_after   -- remaining HP (0 = dead)
bool headshot
bool killed            -- true if this shot was the killing blow
```

---

#### `0x83` PLAYER_DIED_UDP
Best-effort UDP notification of a death; the TCP `0x86` packet is authoritative.
Clients use this for immediate visual feedback (ragdoll, kill feed).

```
[header]
u16  player_id
u16  killer_id         -- 0 = environment
```

---

#### `0x84` ZONE_STATE
Full description of the current safe zone and the next zone.
Sent whenever zone parameters change, and as a keep-alive every 5 seconds.

```
[header]
f32  current_x         -- current safe zone centre
f32  current_y
f32  current_radius
f32  next_x            -- where the zone is shrinking toward
f32  next_y
f32  next_radius
u32  shrink_start_tick -- tick when shrinking begins (0 = already shrinking)
u32  shrink_end_tick   -- tick when shrinking ends
u16  damage_per_tick   -- HP removed per tick while outside the zone
u8   phase             -- 0 = waiting, 1 = warning, 2 = shrinking, 3 = final
```

---

#### `0x85` ITEM_SPAWNED
A new item has appeared on the map (initial loot drop or item dropped by player).

```
[header]
u32  item_instance_id
u16  item_type         -- see item type table §5.5
f32  x
f32  y
u8   quantity          -- for stackable items
```

---

#### `0x86` ITEM_DESPAWNED
An item has been picked up or otherwise removed from the map.

```
[header]
u32  item_instance_id
```

---

#### `0x87` ITEM_PICKUP_RESULT

```
[header]
u32  item_instance_id
bool success
u8   inventory_slot    -- which slot it was placed in (if success)
u8   reason            -- 0 = OK, 1 = too far, 2 = inventory full, 3 = item gone
```

---

#### `0x88` PROJECTILE_SPAWN
A new projectile is in flight. Clients render it immediately for visual
smoothness; the server is authoritative on hit resolution.

```
[header]
u16  projectile_id
u16  owner_id
u16  item_type         -- weapon type that fired it
f32  origin_x
f32  origin_y
f32  velocity_x
f32  velocity_y
u32  spawn_tick        -- tick at which the projectile was created server-side
```

---

#### `0x89` PONG

```
[header]
u32  client_time_ms    -- echo of client's value from PING
u32  server_time_ms    -- server's local clock at time of pong
```

RTT = `now_client_ms - client_time_ms`.
Clock offset ≈ `server_time_ms - (client_time_ms + RTT/2)`.

---

### 5.5 Entity State Encoding

Used inside `WORLD_SNAPSHOT`. Each entity entry starts with the entity ID
and a type byte that defines the remainder of the struct.

```
u16  entity_id
u8   entity_type       -- 0x01 PLAYER, 0x02 PROJECTILE, 0x03 ITEM_ON_GROUND
```

**PLAYER (0x01):**

```
f32  x
f32  y
f32  aim_angle
u8   state             -- 0 alive, 1 downed, 2 dead
u16  hp                -- current HP (0 when dead)
u16  max_hp
u16  shield            -- current shield
u16  max_shield
u8   held_slot         -- which weapon slot is active (0/1/2)
u8   anim_state        -- 0 idle, 1 walk, 2 run, 3 shoot, 4 reload
     -- inventory summary (hashes, not full data — detail comes via ITEM events)
u8   inv_weapon_0      -- item_type of primary (0 = empty)
u8   inv_weapon_1      -- item_type of secondary
u8   inv_ammo_primary  -- ammo in primary mag
u8   inv_ammo_secondary
```

**PROJECTILE (0x02):**

```
f32  x
f32  y
f32  velocity_x
f32  velocity_y
u16  owner_id
```

**ITEM_ON_GROUND (0x03):**

```
f32  x
f32  y
u16  item_type
u8   quantity
```

---

### Item Type Table

| ID | Name | Category |
|---|---|---|
| `0x0001` | Pistol | Weapon |
| `0x0002` | SMG | Weapon |
| `0x0003` | Shotgun | Weapon |
| `0x0004` | Assault Rifle | Weapon |
| `0x0005` | Sniper Rifle | Weapon |
| `0x0006` | Melee | Weapon |
| `0x0010` | Grenade | Throwable |
| `0x0011` | Smoke Grenade | Throwable |
| `0x0020` | Medkit (small) | Healing |
| `0x0021` | Medkit (large) | Healing |
| `0x0022` | Shield Potion | Healing |
| `0x0030` | Ammo (pistol) | Ammo |
| `0x0031` | Ammo (rifle) | Ammo |
| `0x0032` | Ammo (shotgun) | Ammo |
| `0x0033` | Ammo (sniper) | Ammo |
| `0x0040` | Armour (light) | Armour |
| `0x0041` | Armour (heavy) | Armour |

---

## 6. Full Connection Flow

```
CLIENT                                  SERVER (TCP :25565)
  │                                         │
  │──── TCP connect ───────────────────────►│
  │──── HANDSHAKE (0x01, "Alice") ─────────►│
  │◄─── WELCOME (0x81, player_id=5) ────────│
  │◄─── ROSTER_UPDATE (0x82, [Alice]) ──────│ (broadcast to all in lobby)
  │                                         │
  │   [other players join, roster updates]  │
  │                                         │
  │──── PLAYER_READY (0x02) ───────────────►│
  │                                         │
  │◄─── MATCH_FOUND (0x83, match_id,        │
  │         udp_host, udp_port,             │
  │         spawn_x, spawn_y,              │
  │         countdown=5) ──────────────────│
  │◄─── MATCH_COUNTDOWN (0x84, 5) ──────────│
  │◄─── MATCH_COUNTDOWN (0x84, 4) ──────────│
  │         ...                             │
  │◄─── MATCH_START (0x85, tick=0) ─────────│
  │                                         │
  │     [Client opens UDP socket]           │
  │                              SERVER (UDP :25566)
  │                                         │
  │──── INPUT (0x01) ──────────────────────►│  every client tick
  │◄─── WORLD_SNAPSHOT (0x81) ──────────────│  every server tick (20 Hz)
  │◄─── ZONE_STATE (0x84) ──────────────────│  on zone change
  │◄─── ITEM_SPAWNED (0x85) × N ────────────│  initial loot drop
  │                                         │
  │──── SHOOT (0x02) ──────────────────────►│
  │◄─── PROJECTILE_SPAWN (0x88) ────────────│  broadcast to nearby clients
  │◄─── HIT_CONFIRM (0x82) ─────────────────│  only to shooter
  │◄─── WORLD_SNAPSHOT (hp updated) ────────│
  │                                         │
  │──── ITEM_PICKUP (0x03) ────────────────►│
  │◄─── ITEM_PICKUP_RESULT (0x87) ──────────│
  │◄─── ITEM_DESPAWNED (0x86) ──────────────│  broadcast to nearby clients
  │                                         │
  │   [last opponent dies]                  │
  │                                         │
  │◄─── PLAYER_DIED_TCP (0x86) ─────────────│ TCP (authoritative)
  │◄─── MATCH_END (0x87) ───────────────────│ TCP
  │                                         │
  │     [UDP socket closed]                 │
  │     [returns to lobby]                  │
  │◄─── ROSTER_UPDATE (0x82) ───────────────│
```

---

## 7. Gameplay State Machine

```
                  ┌────────────────┐
    TCP connect ──►  LOBBY          │
                  │  (TCP active,  │◄── ROSTER_UPDATE broadcasts
                  │   UDP idle)    │
                  └───────┬────────┘
                          │ MATCH_FOUND (0x83)
                          ▼
                  ┌────────────────┐
                  │  COUNTDOWN     │◄── MATCH_COUNTDOWN every 1 s
                  │  (TCP active,  │
                  │   UDP idle)    │
                  └───────┬────────┘
                          │ MATCH_START (0x85)
                          ▼
                  ┌────────────────┐
        ┌────────►│  IN_GAME       │◄── WORLD_SNAPSHOT, HIT_CONFIRM,
        │         │  (TCP + UDP)   │     ZONE_STATE, ITEM_* events
        │         └───────┬────────┘
        │                 │ PLAYER_DIED_TCP (0x86)
        │                 ▼
        │         ┌────────────────┐
        │         │  SPECTATING    │  Continues receiving WORLD_SNAPSHOTs
        │         │  (UDP read,    │  and HIT_CONFIRMs (for kill feed)
        │         │   no input)    │
        │         └───────┬────────┘
        │                 │ MATCH_END (0x87)
        │                 ▼
        └─────────┌────────────────┐
                  │  RESULTS       │  TCP only; shows stats screen
                  └───────┬────────┘
                          │ (client dismisses results)
                          ▼
                        LOBBY
```

---

## 8. Client-Side Prediction & Server Reconciliation

The goal is to make movement feel instant (no input lag) while keeping the
server authoritative on all outcomes.

### Prediction loop (client side)

```
each client frame:
  1. Read local input (WASD, aim angle, buttons)
  2. Assign input_seq (wrapping u8, incremented each frame)
  3. Simulate movement locally → update predicted position
  4. Store input in a ring buffer: { input_seq, tick, pos, vel, buttons }
  5. Send INPUT packet (predicted pos + actual vel/buttons) to server
  6. Render from predicted state
```

### Server authoritative tick

```
each server tick (20 Hz):
  1. Consume all INPUT packets received since last tick
     (if multiple arrive, use the latest by input_seq)
  2. Re-simulate player movement from last authoritative state
     using received velocity + buttons (IGNORE client pos_x/pos_y)
  3. Validate: check map boundaries, collision, speed cap
  4. Update authoritative player state
  5. Resolve projectiles, hits, pickups
  6. Broadcast WORLD_SNAPSHOT to all clients
```

### Reconciliation loop (client side)

```
on receiving WORLD_SNAPSHOT for tick T:
  1. Discard snapshot if seq < last_processed_seq (out of order)
  2. Read authoritative position for local player from snapshot
  3. Find saved input with input_seq matching snapshot's acknowledged seq
  4. If |auth_pos - predicted_pos| > correction_threshold (e.g. 0.5 units):
       a. Snap to authoritative position
       b. Re-apply all unacknowledged inputs from the ring buffer
          to re-derive current predicted position
  5. If delta is small: smoothly lerp toward authoritative position
     over 3–5 frames (avoids visible teleport)
```

### Other entities (not the local player)

Render at the interpolated position between the two most recent snapshots.
This introduces ~50–100 ms of display lag for other players — acceptable for
a top-down shooter at this player count.

```
render_pos = lerp(snapshot[T-1].pos, snapshot[T].pos,
                  (now - snapshot[T-1].time) / tick_interval)
```

---

## 9. Zone / Storm Mechanics

### Phase schedule (example for a 20-player match)

| Phase | Wait (s) | Shrink (s) | Damage/tick | Notes |
|---|---|---|---|---|
| 0 | 60 | — | 0 | Initial loot phase |
| 1 | 30 | 30 | 1 | First shrink |
| 2 | 20 | 25 | 2 | |
| 3 | 15 | 20 | 3 | |
| 4 | 10 | 15 | 5 | |
| 5 | 0 | 10 | 10 | Final circle |

### Zone packet strategy

- Send `ZONE_STATE` (TCP would be ideal but UDP keeps it in the game stream)
  once on phase transition and then every 5 s as a keep-alive.
- Client interpolates zone circle visually using `shrink_start_tick`,
  `shrink_end_tick`, and current server tick from snapshots.
- Damage is computed server-side each tick; client shows a visual indicator
  when the player is outside the zone.

### Zone server validation

Each tick the server checks: `distance(player_pos, current_zone_centre) > current_zone_radius`.
If true: `player.hp -= zone_damage_per_tick`. If hp ≤ 0, trigger death.

---

## 10. Server Validation Rules

Every packet the server receives is validated before any state change occurs.
Invalid packets are silently dropped (never crash the server).

| Packet | Validation checks |
|---|---|
| `HANDSHAKE` | Nickname not blank, not duplicate, length ≤ 32 chars |
| `INPUT` | Player is alive; speed `√(vx²+vy²)` ≤ max_speed for player state |
| `SHOOT` | Player alive; weapon slot equipped; weapon has ammo; fire cooldown elapsed |
| `ITEM_PICKUP` | Player alive; item exists; `distance(player, item) ≤ pickup_radius (2.0 units)`; inventory has space |
| `ITEM_USE` | Player alive; item in slot; item is usable; use cooldown elapsed |
| `ITEM_DROP` | Player alive; item in slot; quantity ≤ held quantity |
| `RELOAD` | Player alive; weapon in slot; not already reloading; reserve ammo > 0 |

### Anti-cheat / sanity checks

- **Speed cap:** reject INPUT where `|velocity| > max_speed × 1.5` (small tolerance for lag).
- **Teleport detection:** if predicted `pos_x/pos_y` deviates from server state by more than
  `max_speed × rtt_seconds × 2`, log a warning; if repeated, flag the player.
- **Rapid fire:** if SHOOT packets arrive faster than `weapon.fire_interval × 0.8`, drop extras.
- **Proximity check:** all spatial interactions (pickup, melee) verified server-side.
- **Sequence replay:** UDP packets with `seq` ≤ last processed `seq` for that player are dropped.

---

## 11. Sequence Numbers & Packet Loss

### UDP sequence numbers

Each sender maintains a monotonically increasing `u32 seq` per direction.
Receivers track `last_received_seq` and discard packets arriving out of order
(lower seq than last processed).

**Note:** do not confuse the UDP `seq` (per-packet ordering) with the game
`tick` (server simulation step) or the `input_seq` (client input ring buffer
index). All three serve different roles.

### Handling loss

| Lost packet | Impact | Mitigation |
|---|---|---|
| `INPUT` | Server uses last known velocity/buttons for that tick | Tolerable; feels like ~50 ms of coasting |
| `WORLD_SNAPSHOT` | Client holds last snapshot for 1–2 extra frames | `lerp` from last two received snapshots |
| `HIT_CONFIRM` | Player doesn't see hit marker | Server re-sends last 3 confirms in next snapshot header |
| `ITEM_SPAWNED` | Item invisible until next `ZONE_STATE` keep-alive | Server includes nearby item list in full snapshots |
| `PROJECTILE_SPAWN` | Client misses projectile visual | Acceptable; hit resolution is server-side |
| `PLAYER_DIED_TCP` | Player not eliminated | Cannot be lost — sent over TCP |
| `MATCH_END` | Stuck in-game | Cannot be lost — sent over TCP |

---

## 12. Future Considerations

| Feature | Notes |
|---|---|
| **Delta compression** | `WORLD_SNAPSHOT` should only encode entities whose state changed since the last ack'd snapshot. Server tracks per-client baseline snapshot. |
| **Interest management** | At 50 players, don't send every player to every client — only those within view radius (~20 units). Reduces bandwidth significantly. |
| **UDP reliability layer** | For `HIT_CONFIRM` and death events, consider a lightweight ack scheme on UDP rather than falling back to TCP. |
| **Reconnection** | If a client's TCP drops mid-match, allow a 30-second window to reconnect using `match_id + player_id`. State is preserved server-side. |
| **Cheating / anti-cheat** | Consider moving hit detection entirely server-side using lag-compensated rewind (record past N ticks of all player positions). |
| **Compression** | For 50 players, a full `WORLD_SNAPSHOT` is ~50 × 30 bytes = 1.5 KB/tick × 20 Hz = 30 KB/s per client. Acceptable; delta brings this to ~5–8 KB/s. |
| **Team mode** | Add `team_id: u8` to `PLAYER` entity state and `HANDSHAKE`. Roster grouping handled lobby-side. |
| **Voice / chat** | Out of scope for the game server — route through a separate TCP chat channel or a hosted voice service. |