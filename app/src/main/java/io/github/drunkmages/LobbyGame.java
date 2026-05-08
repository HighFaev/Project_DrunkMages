package io.github.drunkmages;

import com.badlogic.gdx.Game;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.networking.NetworkClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LobbyGame extends Game {
    final AtomicInteger myId = new AtomicInteger(-1);
    final AtomicReference<List<PlayerInfo>> roster = new AtomicReference<>(List.of());
    final AtomicReference<String> status  = new AtomicReference<>("Not connected");

    final NetworkClient client = new NetworkClient();
    private Thread netThread;

    public void connect(String host, int port, String nickname) {
        myId.set(-1);
        roster.set(List.of());
        status.set("Connecting...");

        netThread = new Thread(()->{
            try {
                client.connect(host, port, nickname, new NetworkClient.Listener() {
                            @Override public void onWelcome(int id) {
                                myId.set(id);
                                status.set("Connected");
                            }
                            @Override public void onRosterUpdate(List<PlayerInfo> players) {
                                roster.set(players);
                            }
                            @Override public void onDisconnected() {
                                status.set("Disconnected");
                                // Jump back to connect screen on the GL thread
                                com.badlogic.gdx.Gdx.app.postRunnable(() ->
                                        setScreen(new ConnectScreen(LobbyGame.this)));
                            }
                        });
            } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    status.set("Error: " + msg);
                    com.badlogic.gdx.Gdx.app.postRunnable(() ->
                            setScreen(new ConnectScreen(LobbyGame.this, "Could not connect: " + msg)));
                }
        }, "netty-client");
            netThread.setDaemon(true);
            netThread.start();

            setScreen(new LobbyScreen(this));
    }

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

    @Override
    public void create() {
        setScreen(new ConnectScreen(this));
    }
}
