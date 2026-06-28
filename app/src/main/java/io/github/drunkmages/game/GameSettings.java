package io.github.drunkmages.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.Preferences;

public class GameSettings {
    private static final String PREF_NAME = "DrunkMagesSettings";
    private static Preferences prefs;

    // Default keybinds
    public static int keyUp = Input.Keys.W;
    public static int keyDown = Input.Keys.S;
    public static int keyLeft = Input.Keys.A;
    public static int keyRight = Input.Keys.D;
    public static int keyInteract = Input.Keys.F;
    public static int keyReload = Input.Keys.R;
    public static int keyDrop = Input.Keys.G;

    // Tutorial toggle
    public static boolean showTutorial = true;

    public static void load() {
        prefs = Gdx.app.getPreferences(PREF_NAME);
        keyUp = prefs.getInteger("keyUp", Input.Keys.W);
        keyDown = prefs.getInteger("keyDown", Input.Keys.S);
        keyLeft = prefs.getInteger("keyLeft", Input.Keys.A);
        keyRight = prefs.getInteger("keyRight", Input.Keys.D);
        keyInteract = prefs.getInteger("keyInteract", Input.Keys.F);
        keyReload = prefs.getInteger("keyReload", Input.Keys.R);
        keyDrop = prefs.getInteger("keyDrop", Input.Keys.G);
        showTutorial = prefs.getBoolean("showTutorial", true);
    }

    public static void save() {
        prefs.putInteger("keyUp", keyUp);
        prefs.putInteger("keyDown", keyDown);
        prefs.putInteger("keyLeft", keyLeft);
        prefs.putInteger("keyRight", keyRight);
        prefs.putInteger("keyInteract", keyInteract);
        prefs.putInteger("keyReload", keyReload);
        prefs.putInteger("keyDrop", keyDrop);
        prefs.putBoolean("showTutorial", showTutorial);
        prefs.flush();
    }

    // Helper to get string representations (e.g., "W", "Space")
    public static String getKeyName(int keycode) {
        return Input.Keys.toString(keycode);
    }
}