package io.github.drunkmages.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

public class GameHUD {
    public final Stage stage;
    private final SpriteBatch batch;
    private final BitmapFont hudFont;
    private final Skin skin;

    public GameHUD(Runnable onLeaveClicked) {
        batch = new SpriteBatch();
        hudFont = new BitmapFont();
        hudFont.getData().setScale(1.1f);

        stage = new Stage(new ScreenViewport());
        skin = buildButtonSkin(hudFont);

        Table uiRoot = new Table();
        uiRoot.setFillParent(true);
        stage.addActor(uiRoot);

        TextButton leaveBtn = new TextButton("Leave match", skin);
        leaveBtn.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, com.badlogic.gdx.scenes.scene2d.Actor actor) {
                onLeaveClicked.run();
            }
        });
        uiRoot.top().right().pad(14f);
        uiRoot.add(leaveBtn).width(150f).height(38f);
    }

    public void drawText(String line1, String line2, String line3) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        batch.begin();
        hudFont.setColor(Color.WHITE);
        hudFont.draw(batch, line1, 12f, Gdx.graphics.getHeight() - 12f);
        hudFont.draw(batch, line2, 12f, Gdx.graphics.getHeight() - 32f);
        hudFont.setColor(0.75f, 0.75f, 0.8f, 1f);
        hudFont.draw(batch, line3, 12f, Gdx.graphics.getHeight() - 52f);
        batch.end();
    }

    public void resize(int width, int height) {
        stage.getViewport().update(width, height, true);
    }

    public void dispose() {
        stage.dispose();
        batch.dispose();
        hudFont.dispose();
        skin.dispose();
    }

    private static Skin buildButtonSkin(BitmapFont font) {
        Skin skin = new Skin();
        skin.add("default-font", font, BitmapFont.class);
        skin.add("btn-n", solidRegion(0.20f, 0.20f, 0.35f));
        skin.add("btn-o", solidRegion(0.30f, 0.20f, 0.30f));
        skin.add("btn-d", solidRegion(0.13f, 0.13f, 0.22f));

        TextButton.TextButtonStyle tbs = new TextButton.TextButtonStyle();
        tbs.font = font;
        tbs.fontColor = Color.WHITE;
        tbs.up = skin.newDrawable("btn-n");
        tbs.over = skin.newDrawable("btn-o");
        tbs.down = skin.newDrawable("btn-d");
        skin.add("default", tbs, TextButton.TextButtonStyle.class);
        return skin;
    }

    private static TextureRegion solidRegion(float r, float g, float b) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(r, g, b, 1f); pm.fill();
        TextureRegion tr = new TextureRegion(new Texture(pm));
        pm.dispose();
        return tr;
    }
}