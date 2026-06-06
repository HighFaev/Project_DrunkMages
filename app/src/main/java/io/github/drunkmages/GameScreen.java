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
    private int myKills = 0;
    private float hoverTimer = 0f;
    private int lastAimedItemId = -1;

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
        Gdx.graphics.setWindowedMode(1280, 720);
        Gdx.graphics.setResizable(false);
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

        shapes.setProjectionMatrix(worldRenderer.camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        if (game.udp.snapshotItemsPeek() != null) {
            for (NetworkClient.SnapshotItem item : game.udp.snapshotItemsPeek()) {
                int rarity = (item.itemType() >> 8) & 0xFF;
                shapes.setColor(hud.getRarityColor(rarity));
                shapes.circle(item.x(), item.y(), 12f); // Decal base
            }
        }
        shapes.end();

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

        NetworkClient.SnapshotItem aimedItem = null;
        float bestDistSq = 60f * 60f;
        float bestAngleDiff = 0.35f; // Roughly 20 degrees of view cone

        if (game.udp.snapshotItemsPeek() != null) {
            for (NetworkClient.SnapshotItem item : game.udp.snapshotItemsPeek()) {
                float dx = item.x() - myX;
                float dy = item.y() - myY;
                float distSq = dx * dx + dy * dy;

                if (distSq < bestDistSq) {
                    float angleToItem = MathUtils.atan2(dy, dx);
                    float diff = Math.abs(angleToItem - myAimAngle);
                    while (diff > MathUtils.PI) diff -= MathUtils.PI2;
                    while (diff < -MathUtils.PI) diff += MathUtils.PI2;
                    diff = Math.abs(diff);

                    if (diff < bestAngleDiff && clientHasLineOfSight(myX, myY, item.x(), item.y())) {
                        aimedItem = item;
                        bestAngleDiff = diff; // Lock to the closest matched angle
                    }
                }
            }
        }

        // Hover Tooltip Timer updates
        if (aimedItem != null) {
            if (aimedItem.entityId() == lastAimedItemId) {
                hoverTimer += delta;
            } else {
                hoverTimer = 0f;
                lastAimedItemId = aimedItem.entityId();
            }
        } else {
            hoverTimer = 0f;
            lastAimedItemId = -1;
        }

        // F to Pickup now guarded by aiming requirement
        if (Gdx.input.isKeyJustPressed(Keys.F) && aimedItem != null) {
            game.udp.sendPickupRequest();
        }

        // 2. Draw Entities (using the same camera projection)
        shapes.setProjectionMatrix(worldRenderer.camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (ClientBullet b : bullets) b.draw(shapes);
        for (ClientPlayer p : players) p.draw(shapes, myAimAngle);

        shapes.end();

        // Handle Death Events
        PlayerDiedTcpPacket death;
        while ((death = game.deathEvents.poll()) != null) {
            boolean isLocalPlayerKill = death.killerId() == matchInfo.localMatchPlayerId();
            if (isLocalPlayerKill) {
                myKills++;
            }
            hud.addKillFeedEvent(death.victimNickname(), death.killerNickname(), isLocalPlayerKill);
            if (death.playerId() == matchInfo.localMatchPlayerId()) {
                hud.showDeathScreen(death.killerNickname(), () -> game.disconnect());
            }
        }

        int aliveCount = 0;
        List<NetworkClient.SnapshotPlayer> snap = game.udp.snapshotPlayersPeek();
        if (snap != null) {
            for (NetworkClient.SnapshotPlayer p : snap) {
                if (p.hp() > 0) aliveCount++;
            }
        }

        // 3. Draw HUD
        updateHUD();
        ClientPlayer meLocal = players[matchInfo.localMatchPlayerId() & 0xff];
        if (meLocal != null) {
            hud.drawInventory(shapes, meLocal.inventory, meLocal.selectedSlot, meLocal.hp, meLocal.maxHp);
            hud.drawMinimapAndStats(shapes, meLocal.x, meLocal.y, game.udp.zonePeek(), myKills, aliveCount);
        } else {
            hud.drawMinimapAndStats(shapes, myX, myY, game.udp.zonePeek(), myKills, aliveCount);
        }
        hud.stage.act(delta);
        hud.stage.draw();

        if (aimedItem != null && hoverTimer >= 0.5f) {
            int rarity = (aimedItem.itemType() >> 8) & 0xFF;
            int baseType = aimedItem.itemType() & 0xFF;

            String wName = "Unknown";
            String wType = "Weapon";
            if (baseType == 3) { wName = "Pump"; wType = "Shotgun"; }
            else if (baseType == 4) { wName = "AK47"; wType = "Assault Rifle"; }

            String text = hud.getRarityName(rarity) + ": " + wName + " " + wType;
            hud.drawTooltip(text, hud.getRarityColor(rarity));
        }
    }

    private boolean clientHasLineOfSight(float x1, float y1, float x2, float y2) {
        for (float[] wall : WorldRenderer.WALLS) {
            if (lineIntersectsRect(x1, y1, x2, y2, wall[0], wall[1], wall[2], wall[3])) return false;
        }
        return true;
    }

    private boolean lineIntersectsRect(float x1, float y1, float x2, float y2, float rx1, float ry1, float rx2, float ry2) {
        if (x1 >= rx1 && x1 <= rx2 && y1 >= ry1 && y1 <= ry2) return true;
        if (x2 >= rx1 && x2 <= rx2 && y2 >= ry1 && y2 <= ry2) return true;
        return lineIntersectsLine(x1, y1, x2, y2, rx1, ry1, rx2, ry1) ||
                lineIntersectsLine(x1, y1, x2, y2, rx2, ry1, rx2, ry2) ||
                lineIntersectsLine(x1, y1, x2, y2, rx2, ry2, rx1, ry2) ||
                lineIntersectsLine(x1, y1, x2, y2, rx1, ry2, rx1, ry1);
    }

    private boolean lineIntersectsLine(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
        float denom = ((y4 - y3) * (x2 - x1)) - ((x4 - x3) * (y2 - y1));
        if (denom == 0) return false;
        float ua = (((x4 - x3) * (y1 - y3)) - ((y4 - y3) * (x1 - x3))) / denom;
        float ub = (((x2 - x1) * (y1 - y3)) - ((y2 - y1) * (x1 - x3))) / denom;
        return (ua >= 0 && ua <= 1 && ub >= 0 && ub <= 1);
    }

    private void processInputAndNetwork(float delta) {
        int buttons = 0;
        if (Gdx.input.isKeyPressed(Keys.W)) buttons |= 2; // BTN_DOWN
        if (Gdx.input.isKeyPressed(Keys.S)) buttons |= 1; // BTN_UP
        if (Gdx.input.isKeyPressed(Keys.A)) buttons |= 4; // BTN_LEFT
        if (Gdx.input.isKeyPressed(Keys.D)) buttons |= 8; // BTN_RIGHT

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
                // Enemy shooting cosmetic bullets matching weapon type
                players[id].enemyFireCooldown -= delta;
                if (p.anim() == 3 && players[id].enemyFireCooldown <= 0f) {
                    int weaponType = players[id].inventory[players[id].selectedSlot];
                    float fireRate = 0.40f; float bulletSpeed = 240f; int projectiles = 1; float spread = 0.05f;

                    if (weaponType == 3) { fireRate = 1.00f; bulletSpeed = 200f; projectiles = 5; spread = 0.25f; }
                    else if (weaponType == 4) { fireRate = 0.15f; bulletSpeed = 300f; spread = 0.10f; }

                    players[id].enemyFireCooldown = fireRate;

                    for(int proj = 0; proj < projectiles; proj++) {
                        float angle = p.aimRadians() + (MathUtils.random() - 0.5f) * spread;
                        bullets.add(new ClientBullet(
                                players[id].x, players[id].y,
                                MathUtils.cos(angle) * bulletSpeed,
                                MathUtils.sin(angle) * bulletSpeed,
                                id));
                    }
                }
            }
        }

        ClientPlayer me = players[selfSlot & 0xff];

        if (me != null) {
            // Prevent sending shoot input to the server if selected slot is empty
            if (me.hp > 0 && me.inventory[me.selectedSlot] != 0 && Gdx.input.isButtonPressed(Buttons.LEFT)) {
                buttons |= 128; // Shoot
            }

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
        ClientPlayer me = players[matchInfo.localMatchPlayerId() & 0xff];

        if (me != null) {
            myFireCooldown -= delta;

            // Only shoot local cosmetic bullets if alive AND holding a weapon
            if (me.hp > 0 && Gdx.input.isButtonPressed(Buttons.LEFT) && myFireCooldown <= 0f) {
                int weaponType = me.inventory[me.selectedSlot];

                if (weaponType != 0) {
                    float fireRate = 0.40f;
                    float bulletSpeed = 240f;
                    int projectiles = 1;
                    float spread = 0.05f;

                    if (weaponType == 3) { // Shotgun
                        fireRate = 1.00f; bulletSpeed = 200f; projectiles = 5; spread = 0.25f;
                    } else if (weaponType == 4) { // AR
                        fireRate = 0.15f; bulletSpeed = 300f; spread = 0.10f;
                    }

                    myFireCooldown = fireRate;

                    for (int p = 0; p < projectiles; p++) {
                        float angle = myAimAngle + (MathUtils.random() - 0.5f) * spread;
                        bullets.add(new ClientBullet(myX, myY,
                                MathUtils.cos(angle) * bulletSpeed,
                                MathUtils.sin(angle) * bulletSpeed,
                                me.id));
                    }
                }
            }
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
        String line2 = "";


        NetworkClient.GameUdpClient.ZoneStateUdpPacket zone = game.udp.zonePeek();
        if (zone != null && udpInfo != null) {
            int ticksUntilShrink = zone.shrinkStartTick() - udpInfo.serverTick();
            int ticksUntilEnd = zone.shrinkEndTick() - udpInfo.serverTick();
            if (ticksUntilShrink > 0) {
                line2 = "Zone shrinks in: " + (ticksUntilShrink / 20) + "s";
            } else if (ticksUntilEnd > 0) {
                line2 = "Zone is shrinking! (" + (ticksUntilEnd / 20) + "s)";
            } else {
                line2 = "Zone Phase " + zone.phase();
            }
        }

//        String line4 = "WASD move  ·  mouse aim  ·  LMB shoot  ·  Esc leave";

        hud.drawText(line1, line2, "");
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