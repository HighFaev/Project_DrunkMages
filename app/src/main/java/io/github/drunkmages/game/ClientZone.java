package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import io.github.drunkmages.networking.NetworkClient.GameUdpClient.ZoneStateUdpPacket;

public class ClientZone {
    private static final Color CURRENT_ZONE = new Color(0.2f, 0.4f, 1f, 1f); // Solid Blue
    private static final Color NEXT_ZONE = new Color(1f, 1f, 1f, 0.4f);      // Faded White
    private static final Color STORM_FILTER = new Color(0.1f, 0.3f, 1f, 0.35f); // Translucent blue tint

    public void draw(ShapeRenderer shapes, ZoneStateUdpPacket zone) {
        if (zone == null) return;

        // 1. Draw the Storm Filter (Blue tint outside the safe zone)
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(STORM_FILTER);

        int segments = 60;
        float inner = Math.max(0, zone.curRadius());
        float outer = GameConstants.ARENA_SIZE * 2f; // Big enough to cover the entire screen

        for (int i = 0; i < segments; i++) {
            float angle1 = (float)i / segments * MathUtils.PI2;
            float angle2 = (float)(i + 1) / segments * MathUtils.PI2;

            float x1_in = zone.curX() + MathUtils.cos(angle1) * inner;
            float y1_in = zone.curY() + MathUtils.sin(angle1) * inner;
            float x1_out = zone.curX() + MathUtils.cos(angle1) * outer;
            float y1_out = zone.curY() + MathUtils.sin(angle1) * outer;

            float x2_in = zone.curX() + MathUtils.cos(angle2) * inner;
            float y2_in = zone.curY() + MathUtils.sin(angle2) * inner;
            float x2_out = zone.curX() + MathUtils.cos(angle2) * outer;
            float y2_out = zone.curY() + MathUtils.sin(angle2) * outer;

            // Draw two triangles to form a segment of the "donut"
            shapes.triangle(x1_in, y1_in, x1_out, y1_out, x2_in, y2_in);
            shapes.triangle(x2_in, y2_in, x1_out, y1_out, x2_out, y2_out);
        }
        shapes.end();

        // 2. Draw Outlines
        shapes.begin(ShapeRenderer.ShapeType.Line);

        // Current zone boundary
        shapes.setColor(CURRENT_ZONE);
        shapes.circle(zone.curX(), zone.curY(), zone.curRadius(), 60);

        if (zone.nextRadius() < zone.curRadius()) {
            shapes.setColor(NEXT_ZONE);
            shapes.circle(zone.nextX(), zone.nextY(), zone.nextRadius(), 60);
        }

        // ALWAYS draw the next zone target
        shapes.setColor(NEXT_ZONE);
        shapes.circle(zone.nextX(), zone.nextY(), zone.nextRadius(), 60);

        shapes.end();
    }
}