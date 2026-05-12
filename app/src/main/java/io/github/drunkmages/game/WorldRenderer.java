package io.github.drunkmages.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;

public class WorldRenderer {
    private static final float VIEW_HEIGHT = 220f;
    private static final float TILE_SIZE = 20f;
    private static final Color TILE_A = new Color(0.18f, 0.45f, 0.18f, 1f);
    private static final Color TILE_B = new Color(0.21f, 0.50f, 0.21f, 1f);
    private static final Color BORDER = new Color(0.08f, 0.20f, 0.08f, 1f);
    private static final Color WALL = new Color(0.30f, 0.28f, 0.20f, 1f);

    public final OrthographicCamera camera;

    public WorldRenderer() {
        camera = new OrthographicCamera();
    }

    public void resize(int width, int height) {
        float aspect = width / (float) height;
        camera.setToOrtho(false, VIEW_HEIGHT * aspect, VIEW_HEIGHT);
        camera.position.set(0f, 0f, 0f);
        camera.update();
    }

    public void updateCamera(float targetX, float targetY) {
        float halfVW = camera.viewportWidth / 2f;
        float halfVH = camera.viewportHeight / 2f;

        float camX = MathUtils.clamp(targetX, -GameConstants.ARENA_HALF + halfVW, GameConstants.ARENA_HALF - halfVW);
        float camY = MathUtils.clamp(targetY, -GameConstants.ARENA_HALF + halfVH, GameConstants.ARENA_HALF - halfVH);

        if (GameConstants.ARENA_SIZE <= camera.viewportWidth) camX = 0f;
        if (GameConstants.ARENA_SIZE <= camera.viewportHeight) camY = 0f;

        camera.position.set(camX, camY, 0f);
        camera.update();
    }

    public void drawBackground(ShapeRenderer shapes) {
        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Dark surround
        shapes.setColor(BORDER);
        shapes.rect(-GameConstants.ARENA_HALF - 10f, -GameConstants.ARENA_HALF - 10f, GameConstants.ARENA_SIZE + 20f, GameConstants.ARENA_SIZE + 20f);

        // Frustum culled checkerboard
        float halfVW = camera.viewportWidth / 2f;
        float halfVH = camera.viewportHeight / 2f;
        float camLeft = camera.position.x - halfVW;
        float camBottom = camera.position.y - halfVH;

        int tx0 = Math.max(0, (int) ((camLeft + GameConstants.ARENA_HALF) / TILE_SIZE));
        int ty0 = Math.max(0, (int) ((camBottom + GameConstants.ARENA_HALF) / TILE_SIZE));
        int tx1 = Math.min((int)(GameConstants.ARENA_SIZE/TILE_SIZE), tx0 + (int) (camera.viewportWidth / TILE_SIZE) + 2);
        int ty1 = Math.min((int)(GameConstants.ARENA_SIZE/TILE_SIZE), ty0 + (int) (camera.viewportHeight / TILE_SIZE) + 2);

        for (int tx = tx0; tx < tx1; tx++) {
            for (int ty = ty0; ty < ty1; ty++) {
                shapes.setColor((tx + ty) % 2 == 0 ? TILE_A : TILE_B);
                shapes.rect(-GameConstants.ARENA_HALF + tx * TILE_SIZE, -GameConstants.ARENA_HALF + ty * TILE_SIZE, TILE_SIZE, TILE_SIZE);
            }
        }

        // Walls
        final float WALL_THICK = 4f;
        shapes.setColor(WALL);
        shapes.rect(-GameConstants.ARENA_HALF, -GameConstants.ARENA_HALF, GameConstants.ARENA_SIZE, WALL_THICK);
        shapes.rect(-GameConstants.ARENA_HALF, GameConstants.ARENA_HALF - WALL_THICK, GameConstants.ARENA_SIZE, WALL_THICK);
        shapes.rect(-GameConstants.ARENA_HALF, -GameConstants.ARENA_HALF, WALL_THICK, GameConstants.ARENA_SIZE);
        shapes.rect(GameConstants.ARENA_HALF - WALL_THICK, -GameConstants.ARENA_HALF, WALL_THICK, GameConstants.ARENA_SIZE);

        shapes.end();
    }
}