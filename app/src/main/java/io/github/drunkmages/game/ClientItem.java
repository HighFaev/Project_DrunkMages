package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

public class ClientItem {
    public static void draw(ShapeRenderer shapes, float x, float y, int type) {
        // Red = Shotgun, Green = AR
        shapes.setColor(type == 3 ? Color.RED : (type == 4 ? Color.GREEN : Color.GRAY));
        shapes.rect(x - 4, y - 4, 8, 8); // Draw as a small box
    }
}