package io.github.drunkmages;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

public final class App {

    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("Java Royale");
        cfg.setWindowedMode(640, 560);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new LobbyGame(), cfg);
    }

    private App() {}
}