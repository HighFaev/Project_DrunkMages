package io.github.drunkmages;

import java.util.List;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.networking.NetworkClient;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point for the game client.
 *
 * Usage:  App [host [port [nickname]]]
 *
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

        new Lwjgl3Application(new LobbyGame(), cfg);
    }



    private App() {
    }
}
