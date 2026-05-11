package io.github.drunkmages;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.Gdx;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.networking.NetworkClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Top-level libGDX application.  Owns the NetworkClient for its lifetime.
 *
 * Screen flow:
 *   ConnectScreen  →  (onWelcome from server)  →  GameScreen
 *                  ←  (ESC / disconnect)        ←
 */
public final class LobbyGame extends Game {

    // Shared state — written by Netty thread, read by GL thread via atomics.
    final AtomicInteger             myId   = new AtomicInteger(-1);
    final AtomicReference<List<PlayerInfo>> roster = new AtomicReference<>(List.of());
    final AtomicReference<String>   status = new AtomicReference<>("Not connected");

    final NetworkClient client = new NetworkClient();

    @Override
    public void create() {
        setScreen(new ConnectScreen(this));
    }

    /**
     * Called by ConnectScreen when the player clicks Connect.
     * Starts Netty on a daemon thread; switches to GameScreen as soon as
     * the server sends WELCOME.
     */
    public void connect(String host, int port, String nickname) {
        myId.set(-1);
        roster.set(List.of());
        status.set("Connecting...");

        Thread netThread = new Thread(() -> {
            try {
                client.connect(host, port, nickname, new NetworkClient.Listener() {
                    @Override public void onWelcome(int id) {
                        myId.set(id);
                        status.set("Connected");
                        // Switch to the game world as soon as server acknowledges us
                        Gdx.app.postRunnable(() -> setScreen(new GameScreen(LobbyGame.this)));
                    }
                    @Override public void onRosterUpdate(List<PlayerInfo> players) {
                        roster.set(players);
                    }
                    @Override public void onDisconnected() {
                        status.set("Disconnected");
                        Gdx.app.postRunnable(() ->
                                setScreen(new ConnectScreen(LobbyGame.this)));
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                status.set("Error: " + msg);
                Gdx.app.postRunnable(() ->
                        setScreen(new ConnectScreen(LobbyGame.this, "Could not connect: " + msg)));
            }
        }, "netty-client");
        netThread.setDaemon(true);
        netThread.start();
    }

    /** Tears down the connection and returns to the connect form. */
    public void disconnect() {
        client.disconnect();
        myId.set(-1);
        roster.set(List.of());
        status.set("Not connected");
        setScreen(new ConnectScreen(this));
    }

    @Override
    public void dispose() {
        super.dispose();
        client.disconnect();
    }
}