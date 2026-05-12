package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;

public final class GameConstants {
    public static final float ARENA_HALF = 400f; // The expanded map size!
    public static final float ARENA_SIZE = ARENA_HALF * 2f;

    public static final float PLAYER_RADIUS = 7f;
    public static final float COLLIDE_DIST = PLAYER_RADIUS * 2f;
    public static final float AIM_LINE_EXTRA = 18f;

    public static final float BULLET_SPEED = 240f;
    public static final float BULLET_RADIUS = 2.5f;
    public static final float BULLET_LIFETIME = 2.5f;
    public static final float FIRE_RATE = 0.22f;

    public static final Color COLOR_SELF = new Color(1.00f, 0.90f, 0.10f, 1f);
    public static final Color COLOR_OTHER = new Color(0.55f, 0.80f, 1.00f, 1f);
    public static final Color COLOR_COLLIDE = new Color(1.00f, 0.28f, 0.08f, 1f);
    public static final Color COLOR_AIM = new Color(1.00f, 0.60f, 0.00f, 1f);
    public static final Color BULLET_COLOR = new Color(1.00f, 0.95f, 0.30f, 1f);

    private GameConstants() {}
}