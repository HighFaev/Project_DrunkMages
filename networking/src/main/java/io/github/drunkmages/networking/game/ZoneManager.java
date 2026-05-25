package io.github.drunkmages.networking.game;

import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

public class ZoneManager {
    public float curX, curY, curRadius;
    public float nextX, nextY, nextRadius;
    public int shrinkStartTick, shrinkEndTick;
    public int damagePerTick;
    public int phase;

    private int currentTick = 0;

    public ZoneManager() {
        curX = 0; curY = 0; curRadius = MatchRuntime.ARENA_HALF * 1.5f; // Covers entire map
        nextX = 0; nextY = 0; nextRadius = curRadius;
        advancePhase();
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
            advancePhase();
        }
    }

    private void advancePhase() {
        phase++;
        shrinkStartTick = currentTick;

        // Very fast test schedule (20 ticks = 1 second)
        if (phase == 1) { // Wait
            shrinkEndTick = currentTick + 200; // 10s wait
            damagePerTick = 0;
        } else if (phase == 2) { // Shrink 1
            nextRadius = 200f;
            pickNextCenter();
            shrinkEndTick = currentTick + 400; // 20s shrink
            damagePerTick = 2; // 2 damage per tick (40 dps!)
        } else if (phase == 3) { // Wait
            shrinkEndTick = currentTick + 100; // 5s wait
            damagePerTick = 2;
        } else if (phase == 4) { // Final Shrink
            nextRadius = 0f;
            pickNextCenter();
            shrinkEndTick = currentTick + 400; // 20s shrink
            damagePerTick = 5; // Instant death almost
        } else {
            shrinkEndTick = currentTick + 999999; // Game over
        }
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
        ByteBuf payload = alloc.buffer(UdpOpcodes.HEADER_BYTES + 33);
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