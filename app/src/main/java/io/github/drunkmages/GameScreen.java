package io.github.drunkmages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Buttons;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;

import io.github.drunkmages.game.*;
import io.github.drunkmages.networking.MatchFoundPacket;
import io.github.drunkmages.networking.NetworkClient;
import io.github.drunkmages.networking.PlayerDiedTcpPacket;

import java.util.List;

public final class GameScreen implements Screen {

    private final LobbyGame game;
    private final MatchFoundPacket matchInfo;

    private final ClientZone clientZone = new ClientZone();

    // Components
    private final WorldRenderer worldRenderer;
    private final GameHUD hud;
    private final ShapeRenderer shapes;
    private final Vector3 scratch = new Vector3();
    private final SpriteBatch batch;

    // Game State
    private final ClientPlayer[] players = new ClientPlayer[256];
    private final Array<ClientBullet> bullets = new Array<>();
    private float myFireCooldown = 0f;
    private float myAimAngle = 0f;
    private float myX = 0f, myY = 0f;

    public GameScreen(LobbyGame game, MatchFoundPacket matchInfo) {
        this.game = game;
        this.matchInfo = matchInfo;
        this.worldRenderer = new WorldRenderer();
        this.shapes = new ShapeRenderer();

        this.batch = new SpriteBatch();

        for (int i = 0; i < 256; i++) {
            players[i] = new ClientPlayer();
        }

        // Initialize HUD with a callback for the Leave button
        this.hud = new GameHUD(game::disconnect);
    }

    @Override
    public void show() {
        myX = matchInfo.spawnX();
        myY = matchInfo.spawnY();
        worldRenderer.resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        // Setup input to hit HUD first, then local keyboard (Esc key)
        Gdx.input.setInputProcessor(new InputMultiplexer(hud.stage, new InputAdapter() {
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
        processInputAndNetwork(delta);
        updateBullets(delta);
        checkCollisions();

        // Rendering
        Gdx.gl.glClearColor(0.07f, 0.09f, 0.07f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        // 1. Draw Map
        worldRenderer.drawBackground(shapes);

        Gdx.gl.glEnable(GL20.GL_BLEND); // Enable transparency
        shapes.setProjectionMatrix(worldRenderer.camera.combined);
        clientZone.draw(shapes, game.udp.zonePeek());

        batch.setProjectionMatrix(worldRenderer.camera.combined);
        batch.begin();
        for (NetworkClient.SnapshotItem item : game.udp.snapshotItemsPeek()) {
            Texture tex = hud.getWeaponTexture(item.itemType());
            if (tex != null) {
                // Draw a 16x16 world-size weapon centered at item.x, item.y
                batch.draw(tex, item.x() - 8, item.y() - 8, 16, 16);
            }
        }
        batch.end();

        // 2. Draw Entities (using the same camera projection)
        shapes.setProjectionMatrix(worldRenderer.camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        if (Gdx.input.isKeyJustPressed(Keys.F)) {
            game.udp.sendPickupRequest();
        }

        for (ClientBullet b : bullets) b.draw(shapes);
        for (ClientPlayer p : players) p.draw(shapes, myAimAngle);

        shapes.end();

        // Handle Death Events
        PlayerDiedTcpPacket death;
        while ((death = game.deathEvents.poll()) != null) {
            hud.addKillFeedEvent(death.victimNickname(), death.killerNickname());
            if (death.playerId() == matchInfo.localMatchPlayerId()) {
                // Drop the respawn runnable
                hud.showDeathScreen(death.killerNickname(), () -> game.disconnect());
            }
        }

        // 3. Draw HUD
        updateHUD();
        ClientPlayer meLocal = players[matchInfo.localMatchPlayerId() & 0xff];
        if (meLocal != null) {
            hud.drawInventory(meLocal.inventory, meLocal.selectedSlot);
        }
        hud.stage.act(delta);
        hud.stage.draw();
    }

    private void processInputAndNetwork(float delta) {
        int buttons = 0;
        if (Gdx.input.isKeyPressed(Keys.W)) buttons |= 2; // BTN_DOWN
        if (Gdx.input.isKeyPressed(Keys.S)) buttons |= 1; // BTN_UP
        if (Gdx.input.isKeyPressed(Keys.A)) buttons |= 4; // BTN_LEFT
        if (Gdx.input.isKeyPressed(Keys.D)) buttons |= 8; // BTN_RIGHT
        if (Gdx.input.isButtonPressed(Buttons.LEFT)) buttons |= 128; // Shoot

        List<NetworkClient.SnapshotPlayer> snap = game.udp.snapshotPlayersPeek();
        int selfSlot = matchInfo.localMatchPlayerId();

        for (NetworkClient.SnapshotPlayer p : snap) {
            int id = p.entityId() & 0xff;
            boolean isSelf = (p.entityId() == selfSlot);

            // Sync with server snapshot
            players[id].updateFromServer(p, isSelf, delta);

            if (isSelf) {
                myX = players[id].x;
                myY = players[id].y;
            } else if (p.hp() > 0) {
                // Enemy shooting cosmetic bullets
                players[id].enemyFireCooldown -= delta;
                if (p.anim() == 3 && players[id].enemyFireCooldown <= 0f) {
                    players[id].enemyFireCooldown = GameConstants.FIRE_RATE;
                    bullets.add(new ClientBullet(
                            players[id].x, players[id].y,
                            MathUtils.cos(p.aimRadians()) * GameConstants.BULLET_SPEED,
                            MathUtils.sin(p.aimRadians()) * GameConstants.BULLET_SPEED,
                            id));
                }
            }
        }

        ClientPlayer me = players[selfSlot & 0xff];

        if (me != null) {
            // Hotbar selection
            if (Gdx.input.isKeyJustPressed(Keys.NUM_1)) { me.selectedSlot = 0; game.udp.sendSwitchWeaponRequest(0); }
            if (Gdx.input.isKeyJustPressed(Keys.NUM_2)) { me.selectedSlot = 1; game.udp.sendSwitchWeaponRequest(1); }
            if (Gdx.input.isKeyJustPressed(Keys.NUM_3)) { me.selectedSlot = 2; game.udp.sendSwitchWeaponRequest(2); }
            if (Gdx.input.isKeyJustPressed(Keys.NUM_4)) { me.selectedSlot = 3; game.udp.sendSwitchWeaponRequest(3); }
            if (Gdx.input.isKeyJustPressed(Keys.NUM_5)) { me.selectedSlot = 4; game.udp.sendSwitchWeaponRequest(4); }
        }

        // Mouse aiming
        scratch.set(Gdx.input.getX(), Gdx.input.getY(), 0f);
        worldRenderer.camera.unproject(scratch);
        myAimAngle = MathUtils.atan2(scratch.y - myY, scratch.x - myX);

        // Send to server
        game.udp.setDriveInput(myAimAngle, buttons);

        // Follow player with camera
        worldRenderer.updateCamera(myX, myY);
    }

    private void updateBullets(float delta) {
        // My local cosmetic bullets
        myFireCooldown -= delta;
        ClientPlayer me = players[matchInfo.localMatchPlayerId() & 0xff];

        // Only shoot if alive
        if (me != null && me.hp > 0 && Gdx.input.isButtonPressed(Buttons.LEFT) && myFireCooldown <= 0f) {
            myFireCooldown = GameConstants.FIRE_RATE;
            bullets.add(new ClientBullet(myX, myY,
                    MathUtils.cos(myAimAngle) * GameConstants.BULLET_SPEED,
                    MathUtils.sin(myAimAngle) * GameConstants.BULLET_SPEED,
                    me.id)); // PASS ownerId HERE
        }

        // Update all bullets and remove dead/collided ones
        for (int i = bullets.size - 1; i >= 0; i--) {
            ClientBullet b = bullets.get(i);

            // If it flew off-map or ran out of life, remove it
            if (b.update(delta)) {
                bullets.removeIndex(i);
                continue;
            }

            boolean hitWall = false;
            for (float[] wall : WorldRenderer.WALLS) {
                if (b.x >= wall[0] && b.x <= wall[2] && b.y >= wall[1] && b.y <= wall[3]) {
                    hitWall = true;
                    break;
                }
            }

            if (hitWall) {
                bullets.removeIndex(i);
                continue;
            }

            // Check collision with players
            boolean hit = false;
            for (ClientPlayer p : players) {
                // Don't collide with dead players, uninitialized slots, or the shooter themselves
                if (p != null && p.hp > 0 && p.id != b.ownerId) {
                    float dx = p.x - b.x;
                    float dy = p.y - b.y;

                    // Same collision math (14f) used on the server
                    if (dx * dx + dy * dy < 14f * 14f) {
                        hit = true;
                        break;
                    }
                }
            }

            // Disappear if it hit someone
            if (hit) {
                bullets.removeIndex(i);
            }
        }
    }

    private void checkCollisions() {
        // Reset flags
        for (ClientPlayer p : players) p.isColliding = false;

        List<NetworkClient.SnapshotPlayer> snap = game.udp.snapshotPlayersPeek();
        for (int i = 0; i < snap.size(); i++) {
            if (snap.get(i).hp() <= 0) continue;
            for (int j = i + 1; j < snap.size(); j++) {
                if (snap.get(j).hp() <= 0) continue;

                int idA = snap.get(i).entityId() & 0xff;
                int idB = snap.get(j).entityId() & 0xff;

                float dx = players[idB].x - players[idA].x;
                float dy = players[idB].y - players[idA].y;
                if (dx * dx + dy * dy < GameConstants.COLLIDE_DIST * GameConstants.COLLIDE_DIST) {
                    players[idA].isColliding = true;
                    players[idB].isColliding = true;
                }
            }
        }
    }

    private void updateHUD() {
        NetworkClient.UdpHud udpInfo = game.udp.snapshotPeek();
        ClientPlayer me = players[matchInfo.localMatchPlayerId() & 0xff];

        String line1 = (udpInfo == null) ? "UDP: waiting for snapshot…"
                : "tick=" + udpInfo.serverTick() + "  entities=" + udpInfo.entityCount();
        String line2 = (me != null && me.hp >= 0) ? "HP  " + me.hp + " / " + me.maxHp : "HP …";
        String line3 = "WASD move  ·  mouse aim  ·  LMB shoot  ·  Esc leave";

        NetworkClient.GameUdpClient.ZoneStateUdpPacket zone = game.udp.zonePeek();
        if (zone != null && udpInfo != null) {
            int ticksUntilShrink = zone.shrinkStartTick() - udpInfo.serverTick();
            int ticksUntilEnd = zone.shrinkEndTick() - udpInfo.serverTick();
            if (ticksUntilShrink > 0) {
                line3 = "Zone shrinks in: " + (ticksUntilShrink / 20) + "s";
            } else if (ticksUntilEnd > 0) {
                line3 = "Zone is shrinking! (" + (ticksUntilEnd / 20) + "s)";
            } else {
                line3 = "Zone Phase " + zone.phase();
            }
        }

        String line4 = "WASD move  ·  mouse aim  ·  LMB shoot  ·  Esc leave";

        hud.drawText(line1, line2, line3, line4);
    }

    @Override public void resize(int width, int height) {
        worldRenderer.resize(width, height);
        hud.resize(width, height);
    }

    public void showWinScreen(io.github.drunkmages.networking.MatchEndPacket end) {
        hud.showWinScreen(end, () -> game.disconnect());
    }

    @Override public void hide() { Gdx.input.setInputProcessor(null); }
    @Override public void pause() {}
    @Override public void resume() {}

    @Override public void dispose() {
        shapes.dispose();
        hud.dispose();
        batch.dispose();
    }
}