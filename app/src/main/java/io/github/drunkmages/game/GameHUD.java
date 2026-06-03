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
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

import java.util.HashMap;
import java.util.Map;

public class GameHUD {
    public final Stage stage;
    private final SpriteBatch batch;
    private final BitmapFont hudFont;
    private final Skin skin;

    private final Table killFeed;
    private final Table deathScreen;

    private final Map<Integer, Texture> weaponTextures = new HashMap<>();

    public GameHUD(Runnable onLeaveClicked) {
        batch = new SpriteBatch();
        hudFont = new BitmapFont();
        hudFont.getData().setScale(1.1f);

        stage = new Stage(new ScreenViewport());
        skin = buildButtonSkin(hudFont);

        // 1. Top Right Leave Button
        Table topRoot = new Table();
        topRoot.setFillParent(true);
        stage.addActor(topRoot);
        TextButton leaveBtn = new TextButton("Leave match", skin);
        leaveBtn.addListener(new ChangeListener() { @Override public void changed(ChangeEvent e, com.badlogic.gdx.scenes.scene2d.Actor a) { onLeaveClicked.run(); } });
        topRoot.top().right().pad(14f);
        topRoot.add(leaveBtn).width(150f).height(38f);

        // 2. Kill Feed Table
        killFeed = new Table();
        killFeed.setFillParent(true);
        killFeed.top().right().padTop(60f).padRight(14f);
        stage.addActor(killFeed);

        // 3. Death Screen Overlay
        deathScreen = new Table();
        deathScreen.setFillParent(true);
        deathScreen.setVisible(false);

        Pixmap bgPm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        bgPm.setColor(0f, 0f, 0f, 0.85f); bgPm.fill();
        deathScreen.setBackground(new com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable(new Texture(bgPm)));
        bgPm.dispose();
        stage.addActor(deathScreen);

        try {
            weaponTextures.put(1, new Texture(Gdx.files.internal("weapons/pistol.png")));
            weaponTextures.put(3, new Texture(Gdx.files.internal("weapons/shotgun.png")));
            weaponTextures.put(4, new Texture(Gdx.files.internal("weapons/ar.png")));
        } catch (Exception e) {
            System.err.println("Warning: Could not load weapon textures. Did you put them in src/main/resources/weapons/ ?");
        }
    }

    public void addKillFeedEvent(String victim, String killer) {
        Label lbl = new Label(killer + " killed " + victim, new Label.LabelStyle(hudFont, Color.ORANGE));
        killFeed.add(lbl).right().padBottom(4f).row();
        // Fade out and remove after 4 seconds
        lbl.addAction(Actions.sequence(Actions.delay(4f), Actions.fadeOut(1f), Actions.removeActor()));
    }

    public void showDeathScreen(String killerName, Runnable onRespawn, Runnable onLeave) {
        deathScreen.clearChildren();
        deathScreen.setVisible(true);
        deathScreen.add(new Label("YOU DIED", new Label.LabelStyle(hudFont, Color.RED))).padBottom(20f).row();
        deathScreen.add(new Label("Killed by: " + killerName, new Label.LabelStyle(hudFont, Color.WHITE))).padBottom(40f).row();

        TextButton respawnBtn = new TextButton("Respawn", skin);
        respawnBtn.addListener(new ChangeListener() { @Override public void changed(ChangeEvent e, com.badlogic.gdx.scenes.scene2d.Actor a) { onRespawn.run(); } });
        deathScreen.add(respawnBtn).width(200f).height(50f).padBottom(10f).row();

        TextButton leaveBtn = new TextButton("Disconnect", skin);
        leaveBtn.addListener(new ChangeListener() { @Override public void changed(ChangeEvent e, com.badlogic.gdx.scenes.scene2d.Actor a) { onLeave.run(); } });
        deathScreen.add(leaveBtn).width(200f).height(50f).row();
    }

    private final TextureRegion slotNormal = solidRegion(0.15f, 0.15f, 0.2f);
    private final TextureRegion slotSelected = solidRegion(0.4f, 0.4f, 0.2f);

    public void drawInventory(int[] inventory, int selectedSlot) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        batch.begin();

        int slotSize = 48;
        int spacing = 8;
        int totalWidth = (inventory.length * slotSize) + ((inventory.length - 1) * spacing);
        int startX = (Gdx.graphics.getWidth() - totalWidth) / 2;
        int startY = 20;

        for (int i = 0; i < inventory.length; i++) {
            int x = startX + i * (slotSize + spacing);
            batch.draw((i == selectedSlot) ? slotSelected : slotNormal, x, startY, slotSize, slotSize);

            hudFont.getData().setScale(0.8f);
            hudFont.setColor(Color.LIGHT_GRAY);
            hudFont.draw(batch, String.valueOf(i + 1), x + 4, startY + slotSize - 4);
            hudFont.getData().setScale(1.1f);

            int itemType = inventory[i];
            if (itemType != 0) {
                Texture weaponTex = weaponTextures.get(itemType);
                if (weaponTex != null) {
                    // Draw the image slightly smaller than the slot (40x40 inside a 48x48 slot)
                    // Centered using +4 offsets
                    batch.draw(weaponTex, x + 4, startY + 4, 40, 40);
                } else {
                    // Fallback to text if the image failed to load
                    hudFont.setColor(Color.WHITE);
                    hudFont.draw(batch, "Item", x + 6, startY + 28);
                }
            }
        }
        batch.end();
    }

    public void hideDeathScreen() {
        deathScreen.setVisible(false);
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

        for (Texture tex : weaponTextures.values()) {
            tex.dispose();
        }
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

    public Texture getWeaponTexture(int itemType) {
        return weaponTextures.get(itemType);
    }
}