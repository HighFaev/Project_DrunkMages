package io.github.drunkmages.networking.game;

/**
 * Minimal authoritative kinematics for WORLD_SNAPSHOT.
 */
final class PlayerSimState {
    /** Match-local entity id §5. */
    final int entityId;

    volatile float posX;
    volatile float posY;
    volatile float velX;
    volatile float velY;
    volatile float aimAngle;
    volatile boolean isShooting;
    volatile float fireCooldown = 0f;

    volatile int hp = 100;
    final int maxHp = 100;

    PlayerSimState(int entityId, float spawnX, float spawnY) {
        this.entityId = entityId;
        this.posX = spawnX;
        this.posY = spawnY;
    }

    /** Integrate simple linear motion capped by validated client velocity magnitude. */
    void integrate(float dt, float cappedVelX, float cappedVelY, float aimRad) {
        this.aimAngle = aimRad;
        this.velX = cappedVelX;
        this.velY = cappedVelY;
        posX += velX * dt;
        posY += velY * dt;
    }
}
