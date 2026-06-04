package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class ZoneManager {
    public float curX, curY, curRadius;
    public float nextX, nextY, nextRadius;
    private float startX, startY, startRadius;
    public int shrinkStartTick, shrinkEndTick;
    public int damagePerTick;
    public int phase;

    private int currentTick = 0;

    public ZoneManager() {
        curX = 0; curY = 0; curRadius = MatchRuntime.ARENA_HALF * 1.5f; // Covers entire map
        nextX = 0; nextY = 0; nextRadius = curRadius;
        phase = 0;
        setupPhase();
    }

    public void tick() {
        currentTick++;

        // Calculate smooth shrinking
        if (currentTick >= shrinkStartTick && currentTick <= shrinkEndTick) {
            float t = (float)(currentTick - shrinkStartTick) / (shrinkEndTick - shrinkStartTick);
            curX = curX + (nextX - curX) * t;
            curY = curY + (nextY - curY) * t;
            curRadius = curRadius + (nextRadius - curRadius) * t;
        }

        // Trigger next phase when shrink ends
        if (currentTick == shrinkEndTick) {
            curX = nextX; curY = nextY; curRadius = nextRadius;
            phase++;
            setupPhase();
        }
    }

    private void setupPhase() {
        startX = curX; startY = curY; startRadius = curRadius;
        int waitSeconds = 0; int shrinkSeconds = 0;

        // Apply balanced 5-Phase Schedule
        switch (phase) {
            case 0: waitSeconds = 60; shrinkSeconds = 0;  damagePerTick = 0;  nextRadius = curRadius; break; // Looting
            case 1: waitSeconds = 0;  shrinkSeconds = 30; damagePerTick = 1;  nextRadius = MatchRuntime.ARENA_HALF * 0.7f; break;
            case 2: waitSeconds = 20; shrinkSeconds = 25; damagePerTick = 2;  nextRadius = MatchRuntime.ARENA_HALF * 0.4f; break;
            case 3: waitSeconds = 15; shrinkSeconds = 20; damagePerTick = 4;  nextRadius = MatchRuntime.ARENA_HALF * 0.2f; break;
            case 4: waitSeconds = 10; shrinkSeconds = 15; damagePerTick = 7;  nextRadius = MatchRuntime.ARENA_HALF * 0.05f; break;
            case 5: waitSeconds = 0;  shrinkSeconds = 20; damagePerTick = 10; nextRadius = 0f; break;
        }

        if (shrinkSeconds > 0) pickNextCenter();
        else { nextX = curX; nextY = curY; }

        shrinkStartTick = currentTick + (waitSeconds * 20); // 20 Ticks = 1 Second
        shrinkEndTick = shrinkStartTick + (shrinkSeconds * 20);
    }

    private void pickNextCenter() {
        // Pick a point guaranteed to be fully inside the current circle
        double angle = Math.random() * Math.PI * 2;
        double maxDist = curRadius - nextRadius;
        if (maxDist < 0) maxDist = 0;
        float r = (float) (Math.random() * maxDist);
        nextX = curX + (float) Math.cos(angle) * r;
        nextY = curY + (float) Math.sin(angle) * r;
    }

    public ByteBuf encode(ByteBufAllocator alloc, int srvSeq, int matchSignedBits) {
        ByteBuf payload = alloc.buffer(UdpOpcodes.HEADER_BYTES + 35);
        UdpHeader.write(payload, UdpOpcodes.S_ZONE_STATE, srvSeq, currentTick, 0, Integer.toUnsignedLong(matchSignedBits));
        payload.writeFloat(curX);
        payload.writeFloat(curY);
        payload.writeFloat(curRadius);
        payload.writeFloat(nextX);
        payload.writeFloat(nextY);
        payload.writeFloat(nextRadius);
        payload.writeInt(shrinkStartTick);
        payload.writeInt(shrinkEndTick);
        payload.writeShort(damagePerTick);
        payload.writeByte(phase);
        return payload;
    }
}