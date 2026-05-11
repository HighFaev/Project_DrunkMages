package io.github.drunkmages;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.drunkmages.common.PlayerInfo;

/**
 * Lobby screen — shows the connected player list and a Disconnect button.
 *
 * Fix vs previous version:
 *  - show() now calls Gdx.input.setInputProcessor(stage) so the Disconnect
 *    button actually receives click events.  ConnectScreen.hide() sets the
 *    processor to null, so without this call no input ever reached the stage.
 */
public final class LobbyScreen implements Screen {

    private final LobbyGame game;

    private Stage      stage;
    private Table      playerTable;   // inner table rebuilt on roster changes
    private BitmapFont titleFont;
    private BitmapFont bodyFont;
    private Skin       skin;

    private LabelStyle titleStyle;
    private LabelStyle bodyStyle;
    private LabelStyle dimStyle;
    private LabelStyle selfStyle;
    private LabelStyle statusStyle;

    private int              lastMyId   = Integer.MIN_VALUE;
    private List<PlayerInfo> lastRoster = null;
    private String           lastStatus = null;

    public LobbyScreen(LobbyGame game) {
        this.game = game;
    }

    @Override
    public void show() {
        titleFont = new BitmapFont();
        titleFont.getData().setScale(2.0f);

        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.6f);

        titleStyle  = new LabelStyle(titleFont, Color.GOLD);
        bodyStyle   = new LabelStyle(bodyFont,  Color.WHITE);
        dimStyle    = new LabelStyle(bodyFont,  new Color(0.55f, 0.55f, 0.65f, 1f));
        selfStyle   = new LabelStyle(bodyFont,  Color.CYAN);
        statusStyle = new LabelStyle(bodyFont,  new Color(0.5f, 0.85f, 0.5f, 1f));

        skin  = buildButtonSkin(bodyFont);
        stage = new Stage(new ScreenViewport());

        // *** THE FIX: register the stage as input processor so buttons work ***
        Gdx.input.setInputProcessor(stage);

        // Root layout: player list expands to fill space; disconnect button at bottom
        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(28f);
        stage.addActor(root);

        playerTable = new Table();
        playerTable.top().left();
        root.add(playerTable).expand().fill().top().left();
        root.row();

        TextButton disconnectBtn = new TextButton("Disconnect", skin);
        disconnectBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.disconnect();
            }
        });
        root.add(disconnectBtn).left().width(180).height(38).padTop(10f);

        rebuildTable(game.myId.get(), game.roster.get(), game.status.get());
    }

    @Override
    public void render(float delta) {
        int              curId     = game.myId.get();
        List<PlayerInfo> curRoster = game.roster.get();
        String           curStatus = game.status.get();

        boolean rosterChanged = lastRoster == null || !curRoster.equals(lastRoster);
        if (curId != lastMyId || rosterChanged || !curStatus.equals(lastStatus)) {
            rebuildTable(curId, curRoster, curStatus);
        }

        Gdx.gl.glClearColor(0.07f, 0.07f, 0.13f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.act(delta);
        stage.draw();
    }

    @Override public void resize(int w, int h) { stage.getViewport().update(w, h, true); }
    @Override public void pause()  {}
    @Override public void resume() {}
    @Override public void hide()   { Gdx.input.setInputProcessor(null); }

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
        titleFont.dispose();
        bodyFont.dispose();
    }

    // -------------------------------------------------------------------------

    private void rebuildTable(int curId, List<PlayerInfo> players, String curStatus) {
        lastMyId   = curId;
        lastRoster = players;
        lastStatus = curStatus;

        playerTable.clearChildren();

        playerTable.add(new Label("Java Royale  -  Lobby", titleStyle))
                .left().colspan(2).padBottom(16f);
        playerTable.row();

        if (curId < 0) {
            playerTable.add(new Label("Waiting for server...", dimStyle))
                    .left().colspan(2).padBottom(10f);
            playerTable.row();
        } else {
            playerTable.add(new Label("Players online: " + players.size(), bodyStyle))
                    .left().colspan(2).padBottom(6f);
            playerTable.row();

            playerTable.add(new Label("--------------------------------", dimStyle))
                    .left().colspan(2).padBottom(8f);
            playerTable.row();

            for (PlayerInfo p : players) {
                boolean    isMe     = (p.id() == curId);
                LabelStyle style    = isMe ? selfStyle : bodyStyle;
                String     marker   = isMe ? ">" : " ";
                String     nameText = "#" + p.id() + "  " + p.nickname()
                        + (isMe ? "  (you)" : "");

                playerTable.add(new Label(marker,   style)).width(20f).left();
                playerTable.add(new Label(nameText, style)).left().padBottom(4f);
                playerTable.row();
            }

            playerTable.add(new Label("", bodyStyle)).colspan(2).padBottom(10f);
            playerTable.row();
        }

        playerTable.add(new Label(curStatus, statusStyle)).left().colspan(2);
        playerTable.row();
    }

    // -------------------------------------------------------------------------

    private static Skin buildButtonSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);
        skin.add("btn-n", region(0.20f, 0.20f, 0.35f));
        skin.add("btn-o", region(0.30f, 0.20f, 0.30f));
        skin.add("btn-d", region(0.13f, 0.13f, 0.22f));

        TextButtonStyle tbs = new TextButtonStyle();
        tbs.font      = font;
        tbs.fontColor = Color.WHITE;
        tbs.up        = skin.newDrawable("btn-n");
        tbs.over      = skin.newDrawable("btn-o");
        tbs.down      = skin.newDrawable("btn-d");
        skin.add("default", tbs, TextButtonStyle.class);
        return skin;
    }

    private static TextureRegion region(float r, float g, float b) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1f);
        pm.fill();
        TextureRegion tr = new TextureRegion(new Texture(pm));
        pm.dispose();
        return tr;
    }
}