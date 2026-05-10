package io.github.drunkmages.networking.game;

/** Parsed UDP {@code INPUT} body (§5.3) after fixed header. */
public record ClientInputPayload(float velX, float velY, float aimAngle,
        int buttons, int inputSeq) {
}
