package io.github.drunkmages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
//import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Main game screen — rendered entirely client-side for now.

 * World   : checkerboard green tiles, 50×40 tiles at 32 px each (1600×1280 px)
 * Player  : yellow filled circle, WASD movement, mouse aim
 * Bullets : small dark circles fired on left-click (hold to auto-fire)
 * HUD     : top-left player count (live from server roster), ESC hint

 * ESC → disconnect and return to ConnectScreen.
 */
public final class GameScreen implements Screen {

    // -------------------------------------------------------------------------
    // World constants
    // -------------------------------------------------------------------------
    private static final int   TILE_PX      = 32;     // pixels per tile
    private static final int   WORLD_TILES_W = 50;
    private static final int   WORLD_TILES_H = 40;
    private static final float WORLD_W      = TILE_PX * WORLD_TILES_W;   // 1600
    private static final float WORLD_H      = TILE_PX * WORLD_TILES_H;   // 1280

    // Tile colours — slight checkerboard so the grid is visible without lines
    private static final Color TILE_A = new Color(0.20f, 0.50f, 0.20f, 1f);
    private static final Color TILE_B = new Color(0.23f, 0.55f, 0.23f, 1f);

    // World border colour
    private static final Color BORDER = new Color(0.10f, 0.25f, 0.10f, 1f);

    // -------------------------------------------------------------------------
    // Player constants
    // -------------------------------------------------------------------------
    private static final float PLAYER_RADIUS = 14f;
    private static final float PLAYER_SPEED  = 160f;  // px / second
    private static final Color PLAYER_COLOR  = new Color(1f, 0.9f, 0.1f, 1f);   // yellow

    // Aim indicator: short line from player center toward mouse
    private static final float AIM_LINE_LEN  = 24f;
    private static final Color AIM_COLOR     = new Color(1f, 0.6f, 0.0f, 1f);   // orange

    // -------------------------------------------------------------------------
    // Bullet constants
    // -------------------------------------------------------------------------
    private static final float BULLET_RADIUS   = 4f;
    private static final float BULLET_SPEED    = 420f;  // px / second
    private static final float BULLET_LIFETIME = 2.2f;  // seconds before auto-removal
    private static final float FIRE_RATE       = 0.22f; // seconds between shots (hold to auto-fire)
    private static final Color BULLET_COLOR    = new Color(0.08f, 0.08f, 0.08f, 1f); // near-black

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private final LobbyGame game;

    // Cameras
    private OrthographicCamera worldCamera;   // follows player, world cords
    private OrthographicCamera hudCamera;     // fixed screen cords for HUD

    // Renderers
    private ShapeRenderer shapes;
    private SpriteBatch   batch;
    private BitmapFont    hudFont;

    // Player
    private float playerX;
    private float playerY;

    // Bullets — kept in a libGDX Array to avoid Iterator allocation each frame
    private final Array<Bullet> bullets = new Array<>();
    private float fireCooldown = 0f;

    // Reusable vector for mouse→world unprojection (avoids allocation per frame)
    private final Vector3 mouseWorld = new Vector3();

    // -------------------------------------------------------------------------
    // Inner type
    // -------------------------------------------------------------------------

    private static final class Bullet {
        float x, y;
        float vx, vy;
        float life; // seconds remaining

        Bullet(float x, float y, float vx, float vy) {
            this.x  = x;  this.y  = y;
            this.vx = vx; this.vy = vy;
            this.life = BULLET_LIFETIME;
        }

        /** Returns true if the bullet should be removed. */
        boolean update(float delta) {
            x += vx * delta;
            y += vy * delta;
            life -= delta;
            // Remove when lifetime expires or bullet leaves the world
            return life <= 0
                    || x < 0 || x > WORLD_W
                    || y < 0 || y > WORLD_H;
        }
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public GameScreen(LobbyGame game) {
        this.game = game;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        // Spawn player in the centre of the world
        playerX = WORLD_W / 2f;
        playerY = WORLD_H / 2f;

        // World camera — viewport = window size, position follows player
        worldCamera = new OrthographicCamera();
        worldCamera.setToOrtho(false,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // HUD camera — stays fixed in screen coordinates
        hudCamera = new OrthographicCamera();
        hudCamera.setToOrtho(false,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        shapes = new ShapeRenderer();
        batch  = new SpriteBatch();

        hudFont = new BitmapFont();
        hudFont.getData().setScale(1.5f);

        // GameScreen uses raw Gdx.input polling — no Stage needed
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void render(float delta) {
        // -- ESC to disconnect -------------------------------------------------
        if (Gdx.input.isKeyJustPressed(Keys.ESCAPE)) {
            game.disconnect();
            return;
        }

        // -- Update ------------------------------------------------------------
        handleMovement(delta);
        handleShooting(delta);
        updateBullets(delta);
        updateCamera();

        // -- Draw --------------------------------------------------------------
        Gdx.gl.glClearColor(0.1f, 0.1f, 0.1f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        drawWorld();
        drawHud();
    }

    @Override
    public void resize(int width, int height) {
        worldCamera.viewportWidth  = width;
        worldCamera.viewportHeight = height;
        hudCamera.setToOrtho(false, width, height);
        hudCamera.update();
        updateCamera(); // re-clamp after resize
    }

    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   { Gdx.input.setInputProcessor(null); }

    @Override
    public void dispose() {
        shapes.dispose();
        batch.dispose();
        hudFont.dispose();
    }

    // -------------------------------------------------------------------------
    // Update helpers
    // -------------------------------------------------------------------------

    private void handleMovement(float delta) {
        float dx = 0, dy = 0;
        if (Gdx.input.isKeyPressed(Keys.W)) dy += 1;
        if (Gdx.input.isKeyPressed(Keys.S)) dy -= 1;
        if (Gdx.input.isKeyPressed(Keys.A)) dx -= 1;
        if (Gdx.input.isKeyPressed(Keys.D)) dx += 1;

        if (dx != 0 && dy != 0) {
            dx *= MathUtils.cosDeg(45);
            dy *= MathUtils.sinDeg(45);
        }

        playerX += dx * PLAYER_SPEED * delta;
        playerY += dy * PLAYER_SPEED * delta;

        // Clamp to world bounds
        playerX = MathUtils.clamp(playerX, PLAYER_RADIUS, WORLD_W - PLAYER_RADIUS);
        playerY = MathUtils.clamp(playerY, PLAYER_RADIUS, WORLD_H - PLAYER_RADIUS);
    }

    private void handleShooting(float delta) {
        fireCooldown -= delta;

        if (Gdx.input.isButtonPressed(Buttons.LEFT) && fireCooldown <= 0) {
            fireCooldown = FIRE_RATE;

            // mouse from screen → world coordinates
            mouseWorld.set(Gdx.input.getX(), Gdx.input.getY(), 0);
            worldCamera.unproject(mouseWorld);

            float angle = MathUtils.atan2(
                    mouseWorld.y - playerY,
                    mouseWorld.x - playerX);

            bullets.add(new Bullet(
                    playerX, playerY,
                    MathUtils.cos(angle) * BULLET_SPEED,
                    MathUtils.sin(angle) * BULLET_SPEED));
        }
    }

    private void updateBullets(float delta) {
        // Iterate backwards so we can remove without index shifting
        for (int i = bullets.size - 1; i >= 0; i--) {
            if (bullets.get(i).update(delta)) {
                bullets.removeIndex(i);
            }
        }
    }

    private void updateCamera() {
        float halfW = worldCamera.viewportWidth  / 2f;
        float halfH = worldCamera.viewportHeight / 2f;

        // Centre on player, clamped so we never show outside the world
        float camX = MathUtils.clamp(playerX, halfW, WORLD_W - halfW);
        float camY = MathUtils.clamp(playerY, halfH, WORLD_H - halfH);

        // If world is smaller than viewport, just centre it
        if (WORLD_W < worldCamera.viewportWidth)  camX = WORLD_W / 2f;
        if (WORLD_H < worldCamera.viewportHeight) camY = WORLD_H / 2f;

        worldCamera.position.set(camX, camY, 0);
        worldCamera.update();
    }

    // -------------------------------------------------------------------------
    // Draw helpers
    // -------------------------------------------------------------------------

    private void drawWorld() {
        shapes.setProjectionMatrix(worldCamera.combined);

        // ---- Filled pass: tiles + player + bullets --------------------------
        shapes.begin(ShapeType.Filled);

        // World border (slightly outside world bounds)
        shapes.setColor(BORDER);
        shapes.rect(-8, -8, WORLD_W + 16, WORLD_H + 16);

        // Visible tile range (culling — only draw what the camera sees)
        float camLeft   = worldCamera.position.x - worldCamera.viewportWidth  / 2f;
        float camBottom = worldCamera.position.y - worldCamera.viewportHeight / 2f;
        int tileX0 = Math.max(0, (int)(camLeft   / TILE_PX));
        int tileY0 = Math.max(0, (int)(camBottom / TILE_PX));
        int tileX1 = Math.min(WORLD_TILES_W, tileX0 + (int)(worldCamera.viewportWidth  / TILE_PX) + 2);
        int tileY1 = Math.min(WORLD_TILES_H, tileY0 + (int)(worldCamera.viewportHeight / TILE_PX) + 2);

        for (int tx = tileX0; tx < tileX1; tx++) {
            for (int ty = tileY0; ty < tileY1; ty++) {
                shapes.setColor((tx + ty) % 2 == 0 ? TILE_A : TILE_B);
                shapes.rect(tx * TILE_PX, ty * TILE_PX, TILE_PX, TILE_PX);
            }
        }

        // Bullets
        shapes.setColor(BULLET_COLOR);
        for (int i = 0; i < bullets.size; i++) {
            Bullet b = bullets.get(i);
            shapes.circle(b.x, b.y, BULLET_RADIUS, 8);
        }

        // Player body
        shapes.setColor(PLAYER_COLOR);
        shapes.circle(playerX, playerY, PLAYER_RADIUS, 20);

        // Aim indicator line
        mouseWorld.set(Gdx.input.getX(), Gdx.input.getY(), 0);
        worldCamera.unproject(mouseWorld);
        float angle = MathUtils.atan2(mouseWorld.y - playerY, mouseWorld.x - playerX);
        float aimX = playerX + MathUtils.cos(angle) * (PLAYER_RADIUS + AIM_LINE_LEN);
        float aimY = playerY + MathUtils.sin(angle) * (PLAYER_RADIUS + AIM_LINE_LEN);
        shapes.setColor(AIM_COLOR);
        shapes.rectLine(playerX, playerY, aimX, aimY, 3f);

        shapes.end();
    }

    private void drawHud() {
        // Re-enable blending — ShapeRenderer.end() may leave it in an unknown state
        Gdx.gl.glEnable(GL20.GL_BLEND);

        batch.setProjectionMatrix(hudCamera.combined);
        batch.begin();

        float screenH = hudCamera.viewportHeight;
        int playerCount = game.roster.get().size();

        hudFont.setColor(Color.WHITE);
        hudFont.draw(batch, "Players online: " + playerCount,       10, screenH - 10);
//        hudFont.draw(batch, "WASD move  |  Mouse aim  |  LMB fire", 10, screenH - 34);

        hudFont.setColor(new Color(0.7f, 0.7f, 0.7f, 1f));
        hudFont.draw(batch, "ESC  disconnect", 10, screenH - 58);

        batch.end();
    }
}