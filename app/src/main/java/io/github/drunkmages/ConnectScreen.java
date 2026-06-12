package io.github.drunkmages;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Label.LabelStyle;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton.TextButtonStyle;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.github.drunkmages.game.GameSettings;

/**
 * The first screen the player sees.  Three fields:

 *   Host      — IP or hostname of the server machine
 *   Port      — default 25565
 *   Nickname  — display name (validated non-blank, ≤ 32 chars)

 * Hitting Enter in any field, or clicking Connect, calls
 * {@link LobbyGame#connect(String, int, String)}.
 */
public final class ConnectScreen implements Screen {

    private static final int    FIELD_WIDTH  = 280;
    private static final int    LABEL_WIDTH  = 100;
    private static final float  ROW_PAD      = 10f;

    private final LobbyGame game;
    private final String    prefilledError; // shown when returning after a failed connect

    private Stage      stage;
    private BitmapFont titleFont;
    private BitmapFont bodyFont;
    private Skin       skin;

    private TextField hostField;
    private TextField portField;
    private TextField nickField;
    private Label     errorLabel;

    public ConnectScreen(LobbyGame game) {
        this(game, null);
    }

    public ConnectScreen(LobbyGame game, String errorMessage) {
        this.game           = game;
        this.prefilledError = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void show() {
        titleFont = new BitmapFont();
        titleFont.getData().setScale(2.2f);

        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.6f);

        // Build a minimal programmatic Skin so TextField and TextButton work
        // without an external .json skin file.
        skin = buildSkin(bodyFont);

        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage);

        // ---- Root table -----------------------------------------------------
        Table root = new Table();
        root.setFillParent(true);
        root.center();
        stage.addActor(root);

        LabelStyle titleStyle = new LabelStyle(titleFont, Color.GOLD);
        LabelStyle bodyStyle  = new LabelStyle(bodyFont,  Color.WHITE);
        LabelStyle dimStyle   = new LabelStyle(bodyFont,  new Color(0.6f, 0.6f, 0.7f, 1f));
        LabelStyle errorStyle = new LabelStyle(bodyFont,  new Color(1f, 0.4f, 0.4f, 1f));

        // Title
        root.add(new Label("Java Royale", titleStyle)).colspan(2).padBottom(6f);
        root.row();
        root.add(new Label("Connect to server", dimStyle)).colspan(2).padBottom(28f);
        root.row();

        // Host
        root.add(new Label("Host", bodyStyle)).width(LABEL_WIDTH).right().padRight(12f).padBottom(ROW_PAD);
        hostField = new TextField(game.defaultConnectHost, skin);
        root.add(hostField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Port
        root.add(new Label("Port", bodyStyle)).right().padRight(12f).padBottom(ROW_PAD);
        portField = new TextField(game.defaultConnectPort, skin);
        root.add(portField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Nickname
        root.add(new Label("Nickname", bodyStyle)).right().padRight(12f).padBottom(ROW_PAD);
        nickField = new TextField(game.defaultConnectNick, skin);
        nickField.setMaxLength(32);
        nickField.setMessageText("your name");
        root.add(nickField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Error label (hidden until needed)
        errorLabel = new Label(prefilledError != null ? prefilledError : "", errorStyle);
        errorLabel.setVisible(prefilledError != null);
        root.add(errorLabel).colspan(2).padBottom(12f);
        root.row();

        // Connect button
        TextButton connectBtn = new TextButton("Connect", skin);
        root.add(connectBtn).colspan(2).width(FIELD_WIDTH).height(44f).padTop(8f);
        root.row();

        // Helper text
        root.add(new Label("Ask the server host for their IP address.", dimStyle))
                .colspan(2).padTop(20f);

        // ---- Listeners ------------------------------------------------------
        connectBtn.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                attemptConnect();
            }
        });

        // Press Enter in any field to connect
        TextField.TextFieldListener enterListener = (field, c) -> {
            if (c == '\r' || c == '\n') attemptConnect();
        };
        hostField.setTextFieldListener(enterListener);
        portField.setTextFieldListener(enterListener);
        nickField.setTextFieldListener(enterListener);

        // Focus nickname first if host is already filled, else focus host
        stage.setKeyboardFocus(nickField.getText().isEmpty() ? nickField : hostField);
        GameSettings.load();
    }

    @Override
    public void render(float delta) {
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
    // Validation + connect
    // -------------------------------------------------------------------------

    private void attemptConnect() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String nick = nickField.getText().trim();

        // Validate
        if (host.isEmpty()) {
            showError("Host cannot be empty.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("Port must be a number between 1 and 65535.");
            return;
        }
        if (nick.isEmpty()) {
            showError("Nickname cannot be empty.");
            return;
        }
        if (nick.length() > 32) {
            showError("Nickname must be 32 characters or fewer.");
            return;
        }

        hideError();
        game.connect(host, port, nick);
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
    }

    // -------------------------------------------------------------------------
    // Minimal programmatic skin (no external atlas needed)
    // -------------------------------------------------------------------------

    /**
     * Builds a Skin containing just enough styles for TextField and TextButton.
     * Uses only solid-color Pixmap drawables — no external asset files required.
     */
    private static Skin buildSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);

        // Colours
        Color fieldBg     = new Color(0.15f, 0.15f, 0.22f, 1f);
        Color fieldBorder = new Color(0.35f, 0.35f, 0.50f, 1f);
        Color fieldFocus  = new Color(0.45f, 0.45f, 0.70f, 1f);
        Color btnNormal   = new Color(0.20f, 0.20f, 0.35f, 1f);
        Color btnOver     = new Color(0.28f, 0.28f, 0.48f, 1f);
        Color btnDown     = new Color(0.13f, 0.13f, 0.25f, 1f);
        Color cursorColor = Color.WHITE;

        skin.add("field-bg",     solidPixmap(fieldBg),     com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("field-border", solidPixmap(fieldBorder), com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("field-focus",  solidPixmap(fieldFocus),  com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("btn-normal",   solidPixmap(btnNormal),   com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("btn-over",     solidPixmap(btnOver),     com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("btn-down",     solidPixmap(btnDown),     com.badlogic.gdx.graphics.g2d.TextureRegion.class);
        skin.add("cursor-color", solidPixmap(cursorColor), com.badlogic.gdx.graphics.g2d.TextureRegion.class);

        // TextFieldStyle
        TextFieldStyle tfs = new TextFieldStyle();
        tfs.font            = font;
        tfs.fontColor       = Color.WHITE;
        tfs.messageFontColor = new Color(0.5f, 0.5f, 0.6f, 1f);
        tfs.background      = skin.newDrawable("field-bg");
        tfs.background.setLeftWidth(8); tfs.background.setRightWidth(8);
        tfs.background.setTopHeight(6); tfs.background.setBottomHeight(6);
        tfs.focusedBackground = skin.newDrawable("field-focus");
        tfs.focusedBackground.setLeftWidth(8); tfs.focusedBackground.setRightWidth(8);
        tfs.focusedBackground.setTopHeight(6); tfs.focusedBackground.setBottomHeight(6);
        tfs.cursor          = skin.newDrawable("cursor-color");
        tfs.cursor.setMinWidth(2); tfs.cursor.setMinHeight(font.getLineHeight());
        tfs.selection       = skin.newDrawable("field-border");
        skin.add("default", tfs, TextFieldStyle.class);

        // TextButtonStyle
        TextButtonStyle tbs = new TextButtonStyle();
        tbs.font     = font;
        tbs.fontColor = Color.WHITE;
        tbs.up       = skin.newDrawable("btn-normal");
        tbs.over     = skin.newDrawable("btn-over");
        tbs.down     = skin.newDrawable("btn-down");
        skin.add("default", tbs, TextButtonStyle.class);

        return skin;
    }

    /** Creates a 1×1 Texture from a solid colour and returns it as a TextureRegion. */
    private static com.badlogic.gdx.graphics.g2d.TextureRegion solidPixmap(Color color) {
        com.badlogic.gdx.graphics.Pixmap pm = new com.badlogic.gdx.graphics.Pixmap(1, 1,
                com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888);
        pm.setColor(color);
        pm.fill();
        com.badlogic.gdx.graphics.g2d.TextureRegion tr =
                new com.badlogic.gdx.graphics.g2d.TextureRegion(
                        new com.badlogic.gdx.graphics.Texture(pm));
        pm.dispose();
        return tr;
    }
}