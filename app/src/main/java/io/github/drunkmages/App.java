package io.github.drunkmages;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

/**
 * Entry point for the game client.

 * Usage: {@code App [host port nickname]} — prefills the connect form (Maven:
 * {@code -Dexec.args="127.0.0.1 25565 Alice"}).

 * Netty runs on a background daemon thread.  libGDX's Lwjgl3Application runs
 * on the main thread (required by LWJGL3 / OS windowing APIs).  Shared state
 * is exchanged through plain AtomicReference / AtomicInteger — no locking needed.
 */

public final class App {
    public static void main(String[] args) {
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("Java Royale");
        cfg.setWindowedMode(640, 480);
        cfg.setForegroundFPS(60);
        new Lwjgl3Application(new LobbyGame(args), cfg);
    }

    private App() {
    }
}
