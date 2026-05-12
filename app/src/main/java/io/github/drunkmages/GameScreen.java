package io.github.drunkmages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.drunkmages.networking.MatchFoundPacket;
import io.github.drunkmages.networking.NetworkClient;

import java.util.List;

/**
 * Game screen — merges mark-dev visuals with hv-dev UDP multiplayer.
 *
 * Visuals (from mark-dev):
 *   - Checkerboard green tiles filling the server arena
 *   - OrthographicCamera that follows the local player
 *   - Yellow self circle with orange aim line; blue circles for others
 *   - Client-side cosmetic bullets (LMB / hold to auto-fire)
 *
 * Networking (from hv-dev):
 *   - WASD → buttons bitmask sent via {@link NetworkClient.GameUdpClient#setDriveInput}
 *   - Player positions driven by UDP WORLD_SNAPSHOT (server-authoritative)
 *   - Collision highlight: players flash orange-red when their circles overlap
 *     (server-side pushback handled in {@code MatchRuntime.resolvePlayerCollisions})
 */
public final class GameScreen implements Screen {

    // ── Server input bit masks (must match MatchRuntime.applyMotion bit indices) ─
    // W/S are intentionally swapped so screen-up = server "up" velocity
    private static final int BTN_UP    = 1;   // bit 0
    private static final int BTN_DOWN  = 2;   // bit 1
    private static final int BTN_LEFT  = 4;   // bit 2
    private static final int BTN_RIGHT = 8;   // bit 3

    // ── Camera / world ───────────────────────────────────────────────────────
    /** Orthographic viewport height in server world-units. */
    private static final float VIEW_HEIGHT = 220f;
    /** Half-extent of the server arena (walls at ±ARENA_HALF). */
    private static final float ARENA_HALF  = 400f;
    private static final float ARENA_SIZE  = ARENA_HALF * 2f;   // 280

    // ── Decorative checkerboard grid ─────────────────────────────────────────
    /** Size of one tile in server world-units. 14 tiles fit across the 280-unit arena. */
    private static final float TILE_SIZE = 20f;
    private static final int   TILES_W   = (int) (ARENA_SIZE / TILE_SIZE); // 14
    private static final int   TILES_H   = (int) (ARENA_SIZE / TILE_SIZE); // 14

    private static final Color TILE_A  = new Color(0.18f, 0.45f, 0.18f, 1f);   // darker green
    private static final Color TILE_B  = new Color(0.21f, 0.50f, 0.21f, 1f);   // lighter green
    private static final Color BORDER  = new Color(0.08f, 0.20f, 0.08f, 1f);   // very dark surround
    private static final Color WALL    = new Color(0.30f, 0.28f, 0.20f, 1f);   // tan wall strip

    // ── Entity visuals ───────────────────────────────────────────────────────
    private static final float PLAYER_RADIUS  = 7f;
    /** Two players are "colliding" when their centre distance < this. */
    private static final float COLLIDE_DIST   = PLAYER_RADIUS * 2f;
    /** Aim line extends this many units beyond the player radius. */
    private static final float AIM_LINE_EXTRA = 18f;

    private static final Color COLOR_SELF     = new Color(1.00f, 0.90f, 0.10f, 1f); // yellow
    private static final Color COLOR_OTHER    = new Color(0.55f, 0.80f, 1.00f, 1f); // light blue
    private static final Color COLOR_COLLIDE  = new Color(1.00f, 0.28f, 0.08f, 1f); // orange-red flash
    private static final Color COLOR_AIM      = new Color(1.00f, 0.60f, 0.00f, 1f); // orange

    // ── Bullet constants (client-side cosmetic only) ─────────────────────────
    private static final float BULLET_SPEED    = 240f;  // world-units / second
    private static final float BULLET_RADIUS   = 2.5f;
    private static final float BULLET_LIFETIME = 2.5f;  // seconds
    private static final float FIRE_RATE       = 0.22f; // seconds between shots (hold to auto-fire)
    private static final Color BULLET_COLOR    = new Color(1.00f, 0.95f, 0.30f, 1f);

    // ── Fields ───────────────────────────────────────────────────────────────
    private final LobbyGame        game;
    private final MatchFoundPacket matchInfo;

    private static final class RenderEntity {
        float x, y;
        boolean initialized;
    }
    private final RenderEntity[] renderEntities = new RenderEntity[256];
    private final float[] otherFireCooldown = new float[256];

    private OrthographicCamera worldCam;
    private ShapeRenderer      shapes;
    private SpriteBatch        batch;
    private BitmapFont         hudFont;

    private Stage uiStage;
    private Skin  uiSkin;

    /** Reusable scratch vector for mouse → world unprojection. */
    private final Vector3 scratch = new Vector3();

    // Bullet state
    private final Array<Bullet> bullets     = new Array<>();
    private float               fireCooldown = 0f;

    // ── Inner type ───────────────────────────────────────────────────────────
    private static final class Bullet {
        float x, y, vx, vy, life;

        Bullet(float x, float y, float vx, float vy) {
            this.x = x; this.y = y; this.vx = vx; this.vy = vy;
            this.life = BULLET_LIFETIME;
        }

        /**
         * Integrates the bullet by {@code dt} seconds.
         *
         * @return {@code true} if the bullet should be removed
         */
        boolean update(float dt) {
            x += vx * dt;
            y += vy * dt;
            life -= dt;
            // Remove when expired or it leaves the arena (+small margin)
            return life <= 0
                    || x < -ARENA_HALF - 20 || x > ARENA_HALF + 20
                    || y < -ARENA_HALF - 20 || y > ARENA_HALF + 20;
        }
    }

    // ── Constructor ──────────────────────────────────────────────────────────

    public GameScreen(LobbyGame game, MatchFoundPacket matchInfo) {
        this.game      = game;
        this.matchInfo = matchInfo;
    }

    // ── Screen lifecycle ─────────────────────────────────────────────────────

    @Override
    public void show() {
        for (int i = 0; i < 256; i++) {
            renderEntities[i] = new RenderEntity();
        }
        worldCam = new OrthographicCamera();
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        shapes  = new ShapeRenderer();
        batch   = new SpriteBatch();
        hudFont = new BitmapFont();
        hudFont.getData().setScale(1.1f);

        uiSkin  = buildButtonSkin(hudFont);
        uiStage = new Stage(new ScreenViewport());

        // "Leave match" button — top-right corner
        Table uiRoot = new Table();
        uiRoot.setFillParent(true);
        uiStage.addActor(uiRoot);

        TextButton leaveBtn = new TextButton("Leave match", uiSkin);
        leaveBtn.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                game.disconnect();
            }
        });
        uiRoot.top().right().pad(14f);
        uiRoot.add(leaveBtn).width(150f).height(38f);

        // Raw WASD polling — ui stage captures Esc / mouse clicks for the button
        Gdx.input.setInputProcessor(new InputMultiplexer(uiStage, new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Keys.ESCAPE) {
                    game.disconnect();
                    return true;
                }
                return false;
            }
        }));
    }

    @Override
    public void render(float delta) {

        // ── 1. Build server input packet ─────────────────────────────────────
        int buttons = 0;
        // W/S swapped: pressing W sets BTN_DOWN (bit 1) → vy positive → screen-up
        if (Gdx.input.isKeyPressed(Keys.W)) buttons |= BTN_DOWN;
        if (Gdx.input.isKeyPressed(Keys.S)) buttons |= BTN_UP;
        if (Gdx.input.isKeyPressed(Keys.A)) buttons |= BTN_LEFT;
        if (Gdx.input.isKeyPressed(Keys.D)) buttons |= BTN_RIGHT;
        if (Gdx.input.isButtonPressed(Buttons.LEFT)) buttons |= 128;

        // ── 2. Locate self in the latest snapshot ─────────────────────────────
        List<NetworkClient.SnapshotPlayer> snap = game.udp.snapshotPlayersPeek();
        int selfSlot = matchInfo.localMatchPlayerId();

        // Default to spawn cords until the first snapshot arrives
        float selfX = matchInfo.spawnX();
        float selfY = matchInfo.spawnY();

        for (int i = 0; i < snap.size(); i++) {
            NetworkClient.SnapshotPlayer p = snap.get(i);
            if (p.hp() <= 0) continue;
            int id = p.entityId() & 0xff;
            RenderEntity re = renderEntities[id];

//             Smooth movement (Lerp)
            if (!re.initialized) {
                re.x = p.x(); re.y = p.y(); re.initialized = true;
            } else {
                float alpha = 1f - (float) Math.exp(-20f * delta); // 20 matches server Hz
                re.x = MathUtils.lerp(re.x, p.x(), alpha);
                re.y = MathUtils.lerp(re.y, p.y(), alpha);
            }

            if (p.entityId() == selfSlot) {
                selfX = re.x;
                selfY = re.y;
            } else {
                // Spawn enemy bullets
                otherFireCooldown[id] -= delta;
                if (p.anim() == 3 && otherFireCooldown[id] <= 0f) {
                    otherFireCooldown[id] = FIRE_RATE;
                    bullets.add(new Bullet(
                            re.x, re.y,
                            MathUtils.cos(p.aimRadians()) * BULLET_SPEED,
                            MathUtils.sin(p.aimRadians()) * BULLET_SPEED));
                }
            }
        }


        // ── 3. Compute aim angle from mouse in world coords ───────────────────
        scratch.set(Gdx.input.getX(), Gdx.input.getY(), 0f);
        worldCam.unproject(scratch);
        float aimAngle = MathUtils.atan2(scratch.y - selfY, scratch.x - selfX);

        // Send input to server
        game.udp.setDriveInput(aimAngle, buttons);

        // ── 4. Camera follows self (clamped to arena) ─────────────────────────
        float halfVW = worldCam.viewportWidth  / 2f;
        float halfVH = worldCam.viewportHeight / 2f;

        float camX = MathUtils.clamp(selfX, -ARENA_HALF + halfVW, ARENA_HALF - halfVW);
        float camY = MathUtils.clamp(selfY, -ARENA_HALF + halfVH, ARENA_HALF - halfVH);
        // If the viewport is wider/taller than the arena, just centre it
        if (ARENA_SIZE <= worldCam.viewportWidth)  camX = 0f;
        if (ARENA_SIZE <= worldCam.viewportHeight) camY = 0f;

        worldCam.position.set(camX, camY, 0f);
        worldCam.update();

        // ── 5. Client-side cosmetic bullets ──────────────────────────────────
        fireCooldown -= delta;
        if (Gdx.input.isButtonPressed(Buttons.LEFT) && fireCooldown <= 0f) {
            fireCooldown = FIRE_RATE;
            bullets.add(new Bullet(
                    selfX, selfY,
                    MathUtils.cos(aimAngle) * BULLET_SPEED,
                    MathUtils.sin(aimAngle) * BULLET_SPEED));
        }
        for (int i = bullets.size - 1; i >= 0; i--) {
            if (bullets.get(i).update(delta)) bullets.removeIndex(i);
        }


        // ── 6. Collision detection (visual highlight, server handles pushback) ─
        // Build a boolean array — index matches snap.get(i).
        int snapSize = snap.size();
        boolean[] colliding = new boolean[snapSize];
        for (int i = 0; i < snapSize; i++) {
            if (snap.get(i).hp() <= 0) continue;
            for (int j = i + 1; j < snapSize; j++) {
                if (snap.get(j).hp() <= 0) continue;
                NetworkClient.SnapshotPlayer a = snap.get(i);
                NetworkClient.SnapshotPlayer b = snap.get(j);
                float dx = b.x() - a.x();
                float dy = b.y() - a.y();
                if (dx * dx + dy * dy < COLLIDE_DIST * COLLIDE_DIST) {
                    colliding[i] = true;
                    colliding[j] = true;
                }
            }
        }



        // ── 7. Clear ──────────────────────────────────────────────────────────
        Gdx.gl.glClearColor(0.07f, 0.09f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // ── 8. World rendering ────────────────────────────────────────────────
        shapes.setProjectionMatrix(worldCam.combined);
        shapes.begin(ShapeType.Filled);

        // Dark surround outside the arena
        shapes.setColor(BORDER);
        shapes.rect(-ARENA_HALF - 10f, -ARENA_HALF - 10f, ARENA_SIZE + 20f, ARENA_SIZE + 20f);

        // Checkerboard tiles — only draw tiles visible by the camera (frustum culling)
        float camLeft   = worldCam.position.x - halfVW;
        float camBottom = worldCam.position.y - halfVH;
        int tx0 = Math.max(0, (int) ((camLeft   + ARENA_HALF) / TILE_SIZE));
        int ty0 = Math.max(0, (int) ((camBottom + ARENA_HALF) / TILE_SIZE));
        int tx1 = Math.min(TILES_W, tx0 + (int) (worldCam.viewportWidth  / TILE_SIZE) + 2);
        int ty1 = Math.min(TILES_H, ty0 + (int) (worldCam.viewportHeight / TILE_SIZE) + 2);

        for (int tx = tx0; tx < tx1; tx++) {
            for (int ty = ty0; ty < ty1; ty++) {
                shapes.setColor((tx + ty) % 2 == 0 ? TILE_A : TILE_B);
                shapes.rect(
                        -ARENA_HALF + tx * TILE_SIZE,
                        -ARENA_HALF + ty * TILE_SIZE,
                        TILE_SIZE, TILE_SIZE);
            }
        }

        // Wall strips (4 units thick, inset from ARENA_HALF)
        final float WALL_THICK = 4f;
        shapes.setColor(WALL);
        shapes.rect(-ARENA_HALF, -ARENA_HALF,            ARENA_SIZE,  WALL_THICK); // bottom
        shapes.rect(-ARENA_HALF,  ARENA_HALF - WALL_THICK, ARENA_SIZE,  WALL_THICK); // top
        shapes.rect(-ARENA_HALF, -ARENA_HALF,            WALL_THICK,  ARENA_SIZE); // left
        shapes.rect( ARENA_HALF - WALL_THICK, -ARENA_HALF, WALL_THICK,  ARENA_SIZE); // right

        // Client-side bullets
        shapes.setColor(BULLET_COLOR);
        for (int i = 0; i < bullets.size; i++) {
            Bullet b = bullets.get(i);
            shapes.circle(b.x, b.y, BULLET_RADIUS, 8);
        }


        // Players from server snapshot
        for (int i = 0; i < snapSize; i++) {
            NetworkClient.SnapshotPlayer p = snap.get(i);
            if (p.hp() <= 0) continue;
            boolean isSelf = (p.entityId() == selfSlot);
            int id = p.entityId() & 0xff;
            RenderEntity re = renderEntities[id];

            // Body colour: orange-red if in collision, else yellow (self) / blue (other)
            Color bodyColor = colliding[i] ? COLOR_COLLIDE : (isSelf ? COLOR_SELF : COLOR_OTHER);
            float radius = PLAYER_RADIUS; // Removed the -1f size difference

            shapes.setColor(bodyColor);
            // Draw using the smoothed position (re.x, re.y) instead of raw network positions
            shapes.circle(re.x, re.y, radius, 20);

            // Aim indicator only for the local player
            if (isSelf) {
                float tipX = re.x + MathUtils.cos(aimAngle) * (PLAYER_RADIUS + AIM_LINE_EXTRA);
                float tipY = re.y + MathUtils.sin(aimAngle) * (PLAYER_RADIUS + AIM_LINE_EXTRA);
                shapes.setColor(COLOR_AIM);
                shapes.rectLine(re.x, re.y, tipX, tipY, 3f);
            }
        }

        shapes.end();

        // ── 9. HUD (screen-space) ─────────────────────────────────────────────
        NetworkClient.UdpHud hud = game.udp.snapshotPeek();

        int hpSelf = -1, hpMax = -1;
        for (NetworkClient.SnapshotPlayer p : snap) {
            if (p.entityId() == selfSlot) {
                hpSelf = p.hp();
                hpMax  = p.maxHp();
                break;
            }
        }

        String hudLine1 = (hud == null)
                ? "UDP: waiting for snapshot…"
                : "tick=" + hud.serverTick() + "  entities=" + hud.entityCount();
        String hudLine2 = (hpSelf >= 0) ? "HP  " + hpSelf + " / " + hpMax : "HP …";
        String hudLine3 = "WASD move  ·  mouse aim  ·  LMB shoot  ·  Esc leave";

        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.getProjectionMatrix().setToOrtho2D(
                0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        batch.begin();
        hudFont.setColor(Color.WHITE);
        hudFont.draw(batch, hudLine1, 12f, Gdx.graphics.getHeight() - 12f);
        hudFont.draw(batch, hudLine2, 12f, Gdx.graphics.getHeight() - 32f);
        hudFont.setColor(0.75f, 0.75f, 0.8f, 1f);
        hudFont.draw(batch, hudLine3, 12f, Gdx.graphics.getHeight() - 52f);
        batch.end();

        // UI stage last (so the Leave button is on top)
        uiStage.act(delta);
        uiStage.draw();
    }

    @Override
    public void resize(int width, int height) {
        float aspect = width / (float) height;
        worldCam.setToOrtho(false, VIEW_HEIGHT * aspect, VIEW_HEIGHT);
        worldCam.position.set(0f, 0f, 0f);
        worldCam.update();
        if (uiStage != null) uiStage.getViewport().update(width, height, true);
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        shapes.dispose();
        batch.dispose();
        hudFont.dispose();
        uiSkin.dispose();
        uiStage.dispose();
    }

    // ── Minimal programmatic skin (no external atlas) ─────────────────────────

    private static Skin buildButtonSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);

        skin.add("btn-n", solidRegion(0.20f, 0.20f, 0.35f));
        skin.add("btn-o", solidRegion(0.30f, 0.20f, 0.30f));
        skin.add("btn-d", solidRegion(0.13f, 0.13f, 0.22f));

        TextButton.TextButtonStyle tbs = new TextButton.TextButtonStyle();
        tbs.font      = font;
        tbs.fontColor = Color.WHITE;
        tbs.up        = skin.newDrawable("btn-n");
        tbs.over      = skin.newDrawable("btn-o");
        tbs.down      = skin.newDrawable("btn-d");
        skin.add("default", tbs, TextButton.TextButtonStyle.class);

        return skin;
    }

    /** Creates a 1×1 solid-colour Texture and returns it as a TextureRegion. */
    private static TextureRegion solidRegion(float r, float g, float b) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1f);
        pm.fill();
        TextureRegion tr = new TextureRegion(new Texture(pm));
        pm.dispose();
        return tr;
    }
}