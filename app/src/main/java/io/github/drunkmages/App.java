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
        String host = args.length > 0 ? args[0]: "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]): 25565;
        String nick = args.length > 2 ? args[2] : "player";

        AtomicInteger myId = new AtomicInteger(-1);
        AtomicReference<List<PlayerInfo>> roster = new AtomicReference<>(List.of());
        AtomicReference<String> status = new AtomicReference<>("Connecting…");

        Thread netThread = new Thread(() -> {
            try {
                NetworkClient.connect(host, port, nick, new NetworkClient.Listener() {
                    @Override
                    public void onWelcome(int id) {
                        myId.set(id);
                        status.set("Connected");
                    }

                    @Override
                    public void onRosterUpdate(List<PlayerInfo> players) {
                        roster.set(players);
                    }

                    @Override
                    public void onDisconnected() {
                        status.set("Disconnected");
                    }
                });
            } catch (Exception e) {
                status.set("Error: " + e.getMessage());
                e.printStackTrace();
            }
        }, "netty-client");
        netThread.setDaemon(true);
        netThread.start();

        // libGDX window — blocks until the window is closed.
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("Java Royale");
        cfg.setWindowedMode(640, 480);
        cfg.setForegroundFPS(60);

        new Lwjgl3Application(new LobbyGame(myId, roster, status), cfg);

    }

    private App() {
    }
}
