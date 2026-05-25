package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import io.github.drunkmages.networking.NetworkClient.GameUdpClient.ZoneStateUdpPacket;

public class ClientZone {
    private static final Color CURRENT_ZONE = new Color(0.2f, 0.4f, 1f, 1f); // Solid Blue
    private static final Color NEXT_ZONE = new Color(1f, 1f, 1f, 0.4f);      // Faded White

    public void draw(ShapeRenderer shapes, ZoneStateUdpPacket zone) {
        if (zone == null) return;

        shapes.begin(ShapeRenderer.ShapeType.Line); // Draw outlines

        // Draw the next zone target
        if (zone.phase() % 2 == 0) { // If currently shrinking or waiting to shrink
            shapes.setColor(NEXT_ZONE);
            shapes.circle(zone.nextX(), zone.nextY(), zone.nextRadius(), 60);
        }

        // Draw current zone boundary
        shapes.setColor(CURRENT_ZONE);
        // Draw multiple overlapping circles to make the line look "thick"
        shapes.circle(zone.curX(), zone.curY(), zone.curRadius(), 60);
        shapes.circle(zone.curX(), zone.curY(), zone.curRadius() - 1f, 60);
        shapes.circle(zone.curX(), zone.curY(), zone.curRadius() + 1f, 60);

        shapes.end();
    }
}