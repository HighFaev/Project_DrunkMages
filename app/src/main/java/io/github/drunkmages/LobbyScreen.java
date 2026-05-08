package io.github.drunkmages;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.drunkmages.common.PlayerInfo;

/**
 * Lobby screen — shows who is connected to the server.
 *
 * Uses Scene2d (Stage + Table + Label) so we get proper layout and reliable
 * text rendering with the default libGDX bitmap font.
 *
 * Shared state (myId, roster, status) is written by the Netty I/O thread and
 * read on the GL render thread via atomics — no locking needed.
 * We only rebuild the table when the data actually changes.
 */
public final class LobbyScreen implements Screen {

    private final LobbyGame game;

    // Scene2d
    private Stage stage;
    private Table table;

    // Fonts (disposed in dispose())
    private BitmapFont titleFont;
    private BitmapFont bodyFont;
    private Skin skin;

    // Label styles (re-used across rebuilds)
    private LabelStyle titleStyle;
    private LabelStyle bodyStyle;
    private LabelStyle dimStyle;
    private LabelStyle selfStyle;
    private LabelStyle statusStyle;

    // Cached values so we only rebuild the table when something changes
    private int              lastMyId   = Integer.MIN_VALUE;
    private List<PlayerInfo> lastRoster = null;
    private String           lastStatus = null;

    public LobbyScreen(LobbyGame game) {
        this.game = game;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        // Fonts
        titleFont = new BitmapFont();
        titleFont.getData().setScale(2.2f);

        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.7f);

        // Styles
        titleStyle  = new LabelStyle(titleFont, Color.GOLD);
        bodyStyle   = new LabelStyle(bodyFont, Color.WHITE);
        dimStyle    = new LabelStyle(bodyFont, new Color(0.55f, 0.55f, 0.65f, 1f));
        selfStyle   = new LabelStyle(bodyFont, Color.CYAN);
        statusStyle = new LabelStyle(bodyFont, new Color(0.5f, 0.85f, 0.5f, 1f));

        skin = buildButtonSkin(bodyFont);
        // Stage + root table
        stage = new Stage(new ScreenViewport());
        table = new Table();
        table.setFillParent(true);
        table.top().left().pad(28f);
        stage.addActor(table);

        rebuildTable(game.myId.get(), game.roster.get(), game.status.get());
    }

    @Override
    public void render(float delta) {
        // Snapshot atomics once per frame
        int              curId     = game.myId.get();
        List<PlayerInfo> curRoster = game.roster.get();
        String           curStatus = game.status.get();

        // Only rebuild the widget tree when something actually changed
        boolean rosterChanged = lastRoster == null || !curRoster.equals(lastRoster);
        if (curId != lastMyId || rosterChanged || !curStatus.equals(lastStatus)) {
            rebuildTable(curId, curRoster, curStatus);
        }

        Gdx.gl.glClearColor(0.07f, 0.07f, 0.13f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        // ScreenViewport keeps 1:1 pixel mapping and re-centres the table
        stage.getViewport().update(width, height, true);
    }

    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   {}

    @Override
    public void dispose() {
        stage.dispose();
        titleFont.dispose();
        bodyFont.dispose();
    }

    // -------------------------------------------------------------------------
    // Table builder
    // -------------------------------------------------------------------------

    /**
     * Clears and repopulates the Scene2d table from the latest state.
     * Called at most once per frame, only when something changed.
     */
    private void rebuildTable(int curId, List<PlayerInfo> players, String curStatus) {
        lastMyId   = curId;
        lastRoster = players;
        lastStatus = curStatus;

        table.clearChildren();

        // ---- Title ----------------------------------------------------------
        table.add(new Label("Java Royale  -  Lobby", titleStyle))
                .left().colspan(2).padBottom(18f);
        table.row();

        // ---- Body -----------------------------------------------------------
        if (curId < 0) {
            table.add(new Label("Waiting for server...", dimStyle))
                    .left().colspan(2).padBottom(10f);
            table.row();
        } else {
            table.add(new Label("Players online: " + players.size(), bodyStyle))
                    .left().colspan(2).padBottom(6f);
            table.row();

            table.add(new Label("--------------------------------", dimStyle))
                    .left().colspan(2).padBottom(8f);
            table.row();

            for (PlayerInfo p : players) {
                boolean isMe      = (p.id() == curId);
                String  marker    = isMe ? ">" : " ";
                String  nameText  = "#" + p.id() + "  " + p.nickname()
                        + (isMe ? "  (you)" : "");
                LabelStyle style  = isMe ? selfStyle : bodyStyle;

                table.add(new Label(marker, style)).width(20f).left();
                table.add(new Label(nameText, style)).left().padBottom(4f);
                table.row();
            }

            table.add(new Label("", bodyStyle)).colspan(2).padBottom(12f);
            table.row();
        }

        // ---- Status ---------------------------------------------------------
        table.add(new Label(curStatus, statusStyle)).left().colspan(2);
        table.row();
    }

    private static Skin buildButtonSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);

        com.badlogic.gdx.graphics.Pixmap pm;

        pm = new com.badlogic.gdx.graphics.Pixmap(1, 1,
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.20f, 0.20f, 0.35f, 1f)); pm.fill();
        skin.add("btn-n", new com.badlogic.gdx.graphics.g2d.TextureRegion(
                new com.badlogic.gdx.graphics.Texture(pm)));
        pm.dispose();

        pm = new com.badlogic.gdx.graphics.Pixmap(1, 1,
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.30f, 0.20f, 0.30f, 1f)); pm.fill();
        skin.add("btn-o", new com.badlogic.gdx.graphics.g2d.TextureRegion(
                new com.badlogic.gdx.graphics.Texture(pm)));
        pm.dispose();

        pm = new com.badlogic.gdx.graphics.Pixmap(1, 1,
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        pm.setColor(new Color(0.13f, 0.13f, 0.22f, 1f)); pm.fill();
        skin.add("btn-d", new com.badlogic.gdx.graphics.g2d.TextureRegion(
                new com.badlogic.gdx.graphics.Texture(pm)));
        pm.dispose();

        TextButton.TextButtonStyle tbs = new TextButton.TextButtonStyle();
        tbs.font      = font;
        tbs.fontColor = Color.WHITE;
        tbs.up        = skin.newDrawable("btn-n");
        tbs.over      = skin.newDrawable("btn-o");
        tbs.down      = skin.newDrawable("btn-d");
        skin.add("default", tbs, TextButton.TextButtonStyle.class);

        return skin;
    }
}