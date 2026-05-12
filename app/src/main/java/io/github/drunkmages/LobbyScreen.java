package io.github.drunkmages;

import java.util.List;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import io.github.drunkmages.common.PlayerInfo;

/**
 * Lobby: roster updates above; Ready / Disconnect stay on fixed widgets so TCP roster bursts
 * cannot cancel a click mid-gesture.
 */
public final class LobbyScreen implements Screen {

    private final LobbyGame game;

    private Stage stage;
    private Table dynamicSection;
    private TextButton readyButton;

    private BitmapFont titleFont;
    private BitmapFont bodyFont;
    private Skin skin;

    private LabelStyle titleStyle;
    private LabelStyle bodyStyle;
    private LabelStyle dimStyle;
    private LabelStyle selfStyle;
    private LabelStyle statusStyle;

    private int              lastMyId   = Integer.MIN_VALUE;
    private List<PlayerInfo> lastRoster = null;
    private String           lastStatus = null;
    private int              lastCountdown = Integer.MIN_VALUE;

    public LobbyScreen(LobbyGame game) {
        this.game = game;
    }

    @Override
    public void show() {
        titleFont = new BitmapFont();
        titleFont.getData().setScale(2.2f);

        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.7f);

        titleStyle  = new LabelStyle(titleFont, Color.GOLD);
        bodyStyle   = new LabelStyle(bodyFont, Color.WHITE);
        dimStyle    = new LabelStyle(bodyFont, new Color(0.55f, 0.55f, 0.65f, 1f));
        selfStyle   = new LabelStyle(bodyFont, Color.CYAN);
        statusStyle = new LabelStyle(bodyFont, new Color(0.5f, 0.85f, 0.5f, 1f));

        skin = buildButtonSkin(bodyFont);

        dynamicSection = new Table();

        readyButton = new TextButton("Ready", skin);
        readyButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                game.client.sendPlayerReadyPulse();
            }
        });

        TextButton disconnectButton = new TextButton("Disconnect", skin);
        disconnectButton.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                game.disconnect();
            }
        });

        Table buttonStrip = new Table();
        buttonStrip.add(readyButton).width(140f).height(40f).padRight(12f);
        buttonStrip.add(disconnectButton).width(140f).height(40f);

        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(28f);
        root.add(dynamicSection).growX().top().left();
        root.row();
        root.add(buttonStrip).left().padTop(18f);

        stage = new Stage(new ScreenViewport());
        stage.addActor(root);

        InputMultiplexer mux = new InputMultiplexer(stage, new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.SPACE && readyButton.isVisible()) {
                    game.client.sendPlayerReadyPulse();
                    return true;
                }
                return false;
            }
        });
        Gdx.input.setInputProcessor(mux);

        rebuildDynamic(game.myId.get(), game.roster.get(), game.status.get(), game.matchCountdownSec.get());
    }

    @Override
    public void render(float delta) {
        int              curId     = game.myId.get();
        List<PlayerInfo> curRoster = game.roster.get();
        String           curStatus = game.status.get();
        int              cd        = game.matchCountdownSec.get();

        boolean rosterChanged = lastRoster == null || !curRoster.equals(lastRoster);
        if (curId != lastMyId || rosterChanged || !curStatus.equals(lastStatus) || cd != lastCountdown) {
            rebuildDynamic(curId, curRoster, curStatus, cd);
        }

        Gdx.gl.glClearColor(0.07f, 0.07f, 0.13f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
        titleFont.dispose();
        bodyFont.dispose();
    }

    private void rebuildDynamic(int curId, List<PlayerInfo> players, String curStatus, int countdown) {
        lastMyId = curId;
        lastRoster = players;
        lastStatus = curStatus;
        lastCountdown = countdown;

        readyButton.setVisible(curId >= 0);

        dynamicSection.clearChildren();
        dynamicSection.top().left();
        /*
         * Rows that use one wide widget must span both columns; otherwise Scene2d grows column 2
         * and pushes the nickname cells to the far right of the window.
         */
        final int cols = 2;

        dynamicSection.add(new Label("Java Royale  -  Lobby", titleStyle)).colspan(cols).left().padBottom(18f);
        dynamicSection.row();

        if (curId < 0) {
            dynamicSection.add(new Label("Waiting for server...", dimStyle)).colspan(cols).left().padBottom(10f);
            dynamicSection.row();
        } else {
            dynamicSection.add(new Label("Players online: " + players.size(), bodyStyle)).colspan(cols).left().padBottom(6f);
            dynamicSection.row();

            dynamicSection.add(new Label("--------------------------------", dimStyle)).colspan(cols).left().padBottom(8f);
            dynamicSection.row();

            for (PlayerInfo p : players) {
                boolean isMe      = (p.id() == curId);
                String  marker    = isMe ? ">" : " ";
                String  statusTxt = p.lobbyReady() ? "Ready" : "waiting...";
                LabelStyle tagStyle = p.lobbyReady()
                        ? new LabelStyle(bodyFont, new Color(0.45f, 0.95f, 0.55f, 1f))
                        : dimStyle;
                String  nameText  = "#" + p.id() + "  " + p.nickname()
                        + (isMe ? "  (you)" : "");
                LabelStyle style  = isMe ? selfStyle : bodyStyle;

                dynamicSection.add(new Label(marker, style)).width(22f).left().top();
                dynamicSection.add(new Label(nameText, style)).left().top().padBottom(2f);
                dynamicSection.row();
                dynamicSection.add(new Label("", dimStyle)).width(22f).left();
                dynamicSection.add(new Label(statusTxt, tagStyle)).left().padBottom(8f);
                dynamicSection.row();
            }

            dynamicSection.add(new Label("", bodyStyle)).colspan(cols).padBottom(12f);
            dynamicSection.row();

            dynamicSection.add(new Label(
                    "Everyone in this list must press Ready to start. Late joiners wait here until all Ready again.",
                    dimStyle)).colspan(cols).left().padBottom(8f);
            dynamicSection.row();
            dynamicSection.add(new Label("Tip: Space also sends Ready.", dimStyle)).colspan(cols).left().padBottom(4f);
            dynamicSection.row();
        }

        dynamicSection.add(new Label(curStatus, statusStyle)).colspan(cols).left();
        dynamicSection.row();

        if (countdown >= 0) {
            LabelStyle cdStyle = new LabelStyle(bodyFont, new Color(1f, 0.85f, 0.35f, 1f));
            dynamicSection.add(new Label("Match starts in: " + countdown + " s", cdStyle)).colspan(cols).left().padTop(6f);
            dynamicSection.row();
        }
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
