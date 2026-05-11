package io.github.drunkmages;

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
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.ui.TextField.TextFieldStyle;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * First screen: three input fields (host, port, nickname) and a Connect button.
 *
 * Fixes applied vs previous version:
 *  - ROW_PAD reduced so all rows fit in the window at 1.6× font scale.
 *  - Nickname field default is empty (message text "your name" shown as hint).
 *  - Gdx.input.setInputProcessor restored in show() so fields are focusable.
 */
public final class ConnectScreen implements Screen {

    private static final int   FIELD_WIDTH = 260;
    private static final int   LABEL_WIDTH = 110;
    private static final float ROW_PAD     = 6f;   // tighter than before to avoid clipping

    private final LobbyGame game;
    private final String    prefilledError;

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
        titleFont.getData().setScale(2.0f);

        bodyFont = new BitmapFont();
        bodyFont.getData().setScale(1.5f);

        skin = buildSkin(bodyFont);

        stage = new Stage(new ScreenViewport());
        Gdx.input.setInputProcessor(stage); // must be set so text fields receive keyboard events

        LabelStyle titleStyle = new LabelStyle(titleFont, Color.GOLD);
        LabelStyle bodyStyle  = new LabelStyle(bodyFont,  Color.WHITE);
        LabelStyle dimStyle   = new LabelStyle(bodyFont,  new Color(0.6f, 0.6f, 0.7f, 1f));
        LabelStyle errorStyle = new LabelStyle(bodyFont,  new Color(1f, 0.4f, 0.4f, 1f));

        Table root = new Table();
        root.setFillParent(true);
        root.center();
        stage.addActor(root);

        // Title
        root.add(new Label("Java Royale", titleStyle)).colspan(2).padBottom(4f);
        root.row();
        root.add(new Label("Connect to server", dimStyle)).colspan(2).padBottom(20f);
        root.row();

        // Host
        root.add(new Label("Host", bodyStyle)).width(LABEL_WIDTH).right().padRight(10f).padBottom(ROW_PAD);
        hostField = new TextField("127.0.0.1", skin);
        root.add(hostField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Port
        root.add(new Label("Port", bodyStyle)).right().padRight(10f).padBottom(ROW_PAD);
        portField = new TextField("25565", skin);
        root.add(portField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Nickname — empty by default so the player must type their name
        root.add(new Label("Nickname", bodyStyle)).right().padRight(10f).padBottom(ROW_PAD);
        nickField = new TextField("", skin);
        nickField.setMaxLength(32);
        nickField.setMessageText("your name");
        root.add(nickField).width(FIELD_WIDTH).padBottom(ROW_PAD);
        root.row();

        // Error label (hidden until needed; always occupies space to avoid layout jumps)
        errorLabel = new Label(prefilledError != null ? prefilledError : " ", errorStyle);
        errorLabel.setVisible(prefilledError != null);
        root.add(errorLabel).colspan(2).padBottom(8f);
        root.row();

        // Connect button
        TextButton connectBtn = new TextButton("Connect", skin);
        root.add(connectBtn).colspan(2).width(FIELD_WIDTH).height(42f).padTop(4f);
        root.row();

        // Helper hint
        root.add(new Label("Ask the server host for their IP address.", dimStyle))
                .colspan(2).padTop(16f);

        // ---- Listeners ------------------------------------------------------
        connectBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                attemptConnect();
            }
        });

        TextField.TextFieldListener enter = (field, c) -> {
            if (c == '\r' || c == '\n') attemptConnect();
        };
        hostField.setTextFieldListener(enter);
        portField.setTextFieldListener(enter);
        nickField.setTextFieldListener(enter);

        // Default focus: nickname (most likely field to fill in)
        stage.setKeyboardFocus(nickField);
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

    @Override
    public void hide() {
        // Clear the input processor so it isn't left dangling while LobbyScreen is active
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        stage.dispose();
        skin.dispose();
        titleFont.dispose();
        bodyFont.dispose();
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    private void attemptConnect() {
        String host    = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String nick    = nickField.getText().trim();

        if (host.isEmpty()) { showError("Host cannot be empty."); return; }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("Port must be a number between 1 and 65535.");
            return;
        }

        if (nick.isEmpty())       { showError("Nickname cannot be empty.");               return; }
        if (nick.length() > 32)   { showError("Nickname must be 32 characters or fewer."); return; }

        hideError();
        game.connect(host, port, nick);
    }

    private void showError(String msg) { errorLabel.setText(msg); errorLabel.setVisible(true); }
    private void hideError()           { errorLabel.setVisible(false); }

    // -------------------------------------------------------------------------
    // Skin (no external atlas required)
    // -------------------------------------------------------------------------

    private static Skin buildSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);

        skin.add("field-bg",    region(0.15f, 0.15f, 0.22f));
        skin.add("field-focus", region(0.25f, 0.25f, 0.42f));
        skin.add("field-sel",   region(0.35f, 0.35f, 0.55f));
        skin.add("cursor",      region(1f,    1f,    1f   ));
        skin.add("btn-up",      region(0.20f, 0.20f, 0.35f));
        skin.add("btn-over",    region(0.28f, 0.28f, 0.48f));
        skin.add("btn-down",    region(0.13f, 0.13f, 0.25f));

        TextFieldStyle tfs     = new TextFieldStyle();
        tfs.font               = font;
        tfs.fontColor          = Color.WHITE;
        tfs.messageFontColor   = new Color(0.5f, 0.5f, 0.6f, 1f);
        tfs.background         = padded(skin.newDrawable("field-bg"));
        tfs.focusedBackground  = padded(skin.newDrawable("field-focus"));
        tfs.selection          = skin.newDrawable("field-sel");
        tfs.cursor             = skin.newDrawable("cursor");
        tfs.cursor.setMinWidth(2);
        skin.add("default", tfs, TextFieldStyle.class);

        TextButtonStyle tbs = new TextButtonStyle();
        tbs.font      = font;
        tbs.fontColor = Color.WHITE;
        tbs.up        = skin.newDrawable("btn-up");
        tbs.over      = skin.newDrawable("btn-over");
        tbs.down      = skin.newDrawable("btn-down");
        skin.add("default", tbs, TextButtonStyle.class);

        return skin;
    }

    /** 1×1 solid-colour TextureRegion added directly to the skin. */
    private static TextureRegion region(float r, float g, float b) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1f);
        pm.fill();
        TextureRegion tr = new TextureRegion(new Texture(pm));
        pm.dispose();
        return tr;
    }

    private static com.badlogic.gdx.scenes.scene2d.utils.Drawable padded(
            com.badlogic.gdx.scenes.scene2d.utils.Drawable d) {
        d.setLeftWidth(8); d.setRightWidth(8);
        d.setTopHeight(6); d.setBottomHeight(6);
        return d;
    }
}