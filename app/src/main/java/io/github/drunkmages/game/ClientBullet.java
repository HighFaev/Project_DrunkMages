package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

public class ClientBullet {
    public float x, y, vx, vy, life;

    public ClientBullet(float x, float y, float vx, float vy) {
        this.x = x; this.y = y; this.vx = vx; this.vy = vy;
        this.life = GameConstants.BULLET_LIFETIME;
    }

    public boolean update(float dt) {
        x += vx * dt;
        y += vy * dt;
        life -= dt;
        return life <= 0
                || x < -GameConstants.ARENA_HALF - 20 || x > GameConstants.ARENA_HALF + 20
                || y < -GameConstants.ARENA_HALF - 20 || y > GameConstants.ARENA_HALF + 20;
    }

    public void draw(ShapeRenderer shapes) {
        shapes.setColor(GameConstants.BULLET_COLOR);
        shapes.circle(x, y, GameConstants.BULLET_RADIUS, 8);
    }
}