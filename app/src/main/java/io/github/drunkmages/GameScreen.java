package io.github.drunkmages;

import com.badlogic.gdx.Gdx;
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
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.drunkmages.networking.MatchFoundPacket;
import io.github.drunkmages.networking.NetworkClient;

import java.util.List;

/**
 * Minimal top-down arena view for phase 3: read UDP snapshots, send WASD + mouse aim.
 */
public final class GameScreen implements Screen {

    /** Server bits: up=1, down=2 — we map keys so screen-up feels natural with our camera. */
    private static final int BTN_UP = 1;
    private static final int BTN_DOWN = 2;
    private static final int BTN_LEFT = 4;
    private static final int BTN_RIGHT = 8;

    private static final float VIEW_HEIGHT = 220f;

    private final LobbyGame game;
    private final MatchFoundPacket matchInfo;

    private OrthographicCamera worldCam;
    private ShapeRenderer shapes;
    private SpriteBatch batch;
    private BitmapFont hudFont;

    private Stage uiStage;
    private Table uiRoot;
    private Skin uiSkin;

    private final Vector3 scratch = new Vector3();

    public GameScreen(LobbyGame game, MatchFoundPacket matchInfo) {
        this.game = game;
        this.matchInfo = matchInfo;
    }

    @Override
    public void show() {
        worldCam = new OrthographicCamera();
        resize(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        shapes = new ShapeRenderer();
        batch = new SpriteBatch();
        hudFont = new BitmapFont();
        hudFont.getData().setScale(1.1f);

        uiSkin = buildButtonSkin(hudFont);

        uiStage = new Stage(new ScreenViewport());
        uiRoot = new Table();
        uiRoot.setFillParent(true);
        uiStage.addActor(uiRoot);

        TextButton leave = new TextButton("Leave match", uiSkin);
        leave.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                game.disconnect();
            }
        });
        uiRoot.top().right().pad(16f);
        uiRoot.add(leave).width(160f).height(40f);

        InputMultiplexer mux = new InputMultiplexer(uiStage, new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == com.badlogic.gdx.Input.Keys.ESCAPE) {
                    game.disconnect();
                    return true;
                }
                return false;
            }
        });
        Gdx.input.setInputProcessor(mux);
    }

    @Override
    public void render(float delta) {
        int buttons = 0;
        /* W/S swapped vs raw server bits so screen-up matches server "up" velocity */
        if (Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.W)) {
            buttons |= BTN_DOWN;
        }
        if (Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.S)) {
            buttons |= BTN_UP;
        }
        if (Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.A)) {
            buttons |= BTN_LEFT;
        }
        if (Gdx.input.isKeyPressed(com.badlogic.gdx.Input.Keys.D)) {
            buttons |= BTN_RIGHT;
        }

        float px = matchInfo.spawnX();
        float py = matchInfo.spawnY();
        List<NetworkClient.SnapshotPlayer> plist = game.udp.snapshotPlayersPeek();
        int selfSlot = matchInfo.localMatchPlayerId();
        for (NetworkClient.SnapshotPlayer p : plist) {
            if (p.entityId() == selfSlot) {
                px = p.x();
                py = p.y();
                break;
            }
        }

        scratch.set(Gdx.input.getX(), Gdx.input.getY(), 0f);
        worldCam.unproject(scratch);
        float aim = (float) Math.atan2(scratch.y - py, scratch.x - px);
        game.udp.setDriveInput(aim, buttons);

        Gdx.gl.glClearColor(0.05f, 0.06f, 0.08f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        shapes.setProjectionMatrix(worldCam.combined);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(0.12f, 0.14f, 0.18f, 1f);
        shapes.rect(-140f, -140f, 280f, 280f);

        shapes.setColor(0.35f, 0.38f, 0.45f, 1f);
        shapes.rect(-140f, -140f, 280f, 4f);
        shapes.rect(-140f, 136f, 280f, 4f);
        shapes.rect(-140f, -140f, 4f, 280f);
        shapes.rect(136f, -140f, 4f, 280f);

        for (NetworkClient.SnapshotPlayer p : plist) {
            boolean self = p.entityId() == selfSlot;
            shapes.setColor(self ? Color.CYAN : Color.LIGHT_GRAY);
            shapes.circle(p.x(), p.y(), self ? 7f : 6f);
        }

        shapes.end();

        NetworkClient.UdpHud hud = game.udp.snapshotPeek();
        int hpSelf = -1;
        int hpMax = -1;
        for (NetworkClient.SnapshotPlayer p : plist) {
            if (p.entityId() == selfSlot) {
                hpSelf = p.hp();
                hpMax = p.maxHp();
                break;
            }
        }

        String hudLine1 = hud == null
                ? "UDP: waiting for snapshot..."
                : "seq=" + hud.serverSeq() + "  tick=" + hud.serverTick() + "  entities=" + hud.entityCount();
        String hudLine2 = hpSelf >= 0 ? "HP " + hpSelf + " / " + hpMax : "HP ...";
        String hudLine3 = "WASD move · mouse aim · Esc leave";

        batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        batch.begin();
        hudFont.setColor(Color.WHITE);
        hudFont.draw(batch, hudLine1, 12f, Gdx.graphics.getHeight() - 12f);
        hudFont.draw(batch, hudLine2, 12f, Gdx.graphics.getHeight() - 32f);
        hudFont.setColor(0.75f, 0.75f, 0.8f, 1f);
        hudFont.draw(batch, hudLine3, 12f, Gdx.graphics.getHeight() - 52f);
        batch.end();

        uiStage.act(delta);
        uiStage.draw();
    }

    @Override
    public void resize(int width, int height) {
        float aspect = width / (float) height;
        worldCam.setToOrtho(false, VIEW_HEIGHT * aspect, VIEW_HEIGHT);
        worldCam.position.set(0f, 0f, 0f);
        worldCam.update();
        if (uiStage != null) {
            uiStage.getViewport().update(width, height, true);
        }
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

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

    /** Solid-colour button skin (same idea as the lobby connect screen). */
    private static Skin buildButtonSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);

        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.20f, 0.20f, 0.35f, 1f));
        pm.fill();
        skin.add("btn-n", new TextureRegion(new Texture(pm)));
        pm.dispose();

        pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.30f, 0.20f, 0.30f, 1f));
        pm.fill();
        skin.add("btn-o", new TextureRegion(new Texture(pm)));
        pm.dispose();

        pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.13f, 0.13f, 0.22f, 1f));
        pm.fill();
        skin.add("btn-d", new TextureRegion(new Texture(pm)));
        pm.dispose();

        TextButton.TextButtonStyle tbs = new TextButton.TextButtonStyle();
        tbs.font = font;
        tbs.fontColor = Color.WHITE;
        tbs.up = skin.newDrawable("btn-n");
        tbs.over = skin.newDrawable("btn-o");
        tbs.down = skin.newDrawable("btn-d");
        skin.add("default", tbs, TextButton.TextButtonStyle.class);

        return skin;
    }
}
