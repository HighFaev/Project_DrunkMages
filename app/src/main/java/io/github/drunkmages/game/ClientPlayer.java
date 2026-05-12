package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import io.github.drunkmages.networking.NetworkClient;

public class ClientPlayer {
    public int id;
    public float x, y, aimRadians;
    public int hp, maxHp;
    public boolean isSelf;
    public boolean isColliding;

    private boolean initialized = false;
    public float enemyFireCooldown = 0f;

    public void updateFromServer(NetworkClient.SnapshotPlayer p, boolean isSelf, float delta) {
        this.id = p.entityId() & 0xff;
        this.aimRadians = p.aimRadians();
        this.hp = p.hp();
        this.maxHp = p.maxHp();
        this.isSelf = isSelf;

        if (!initialized) {
            this.x = p.x();
            this.y = p.y();
            this.initialized = true;
        } else {
            // Smooth movement (Lerp)
            float alpha = 1f - (float) Math.exp(-20f * delta);
            this.x = MathUtils.lerp(this.x, p.x(), alpha);
            this.y = MathUtils.lerp(this.y, p.y(), alpha);
        }
    }

    public void draw(ShapeRenderer shapes, float mouseAimAngle) {
        if (hp <= 0) return; // Don't draw dead players

        Color bodyColor = isColliding ? GameConstants.COLOR_COLLIDE : (isSelf ? GameConstants.COLOR_SELF : GameConstants.COLOR_OTHER);
        shapes.setColor(bodyColor);
        shapes.circle(x, y, GameConstants.PLAYER_RADIUS, 20);

        if (isSelf) {
            float tipX = x + MathUtils.cos(mouseAimAngle) * (GameConstants.PLAYER_RADIUS + GameConstants.AIM_LINE_EXTRA);
            float tipY = y + MathUtils.sin(mouseAimAngle) * (GameConstants.PLAYER_RADIUS + GameConstants.AIM_LINE_EXTRA);
            shapes.setColor(GameConstants.COLOR_AIM);
            shapes.rectLine(x, y, tipX, tipY, 3f);
        }
    }
}