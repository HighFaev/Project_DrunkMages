package io.github.drunkmages;

import com.badlogic.gdx.Game;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.networking.MatchFoundPacket;
import io.github.drunkmages.networking.MatchStartPacket;
import io.github.drunkmages.networking.NetworkClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LobbyGame extends Game {

    /** Defaults for {@link ConnectScreen} when launched with {@code host port nick}. */
    final String defaultConnectHost;
    final String defaultConnectPort;
    final String defaultConnectNick;

    final AtomicInteger myId = new AtomicInteger(-1);
    final AtomicReference<List<PlayerInfo>> roster = new AtomicReference<>(List.of());
    final AtomicReference<String> status = new AtomicReference<>("Not connected");
    /** Match countdown seconds from server; {@code -1} means no active countdown. */
    final AtomicInteger matchCountdownSec = new AtomicInteger(-1);
    final AtomicReference<MatchFoundPacket> pendingMatch = new AtomicReference<>();

    final NetworkClient client = new NetworkClient();
    final NetworkClient.GameUdpClient udp = new NetworkClient.GameUdpClient();

    private Thread netThread;

    public LobbyGame(String... cmdArgs) {
        if (cmdArgs != null
                && cmdArgs.length >= 3
                && !cmdArgs[0].isBlank()
                && !cmdArgs[1].isBlank()
                && !cmdArgs[2].isBlank()) {
            defaultConnectHost = cmdArgs[0].trim();
            defaultConnectPort = cmdArgs[1].trim();
            defaultConnectNick = cmdArgs[2].trim();
        } else {
            defaultConnectHost = "127.0.0.1";
            defaultConnectPort = "25565";
            defaultConnectNick = "mark";
        }
    }

    public void connect(String host, int port, String nickname) {
        myId.set(-1);
        roster.set(List.of());
        status.set("Connecting...");
        pendingMatch.set(null);
        matchCountdownSec.set(-1);

        client.attachUdpBuddy(udp);

        netThread = new Thread(() -> {
            try {
                client.connectBlocking(host, port, nickname, new NetworkClient.LobbyListener() {

                    @Override
                    public void onWelcome(int lobbyAssignedId) {
                        myId.set(lobbyAssignedId);
                        status.set("Connected - when everyone is here, all press Ready.");
                    }

                    @Override
                    public void onRoster(List<PlayerInfo> everybody) {
                        roster.set(everybody);
                        if (myId.get() >= 0) {
                            status.set("Lobby: " + everybody.size()
                                    + " player(s). Match starts only after everyone presses Ready.");
                        }
                    }

                    @Override
                    public void onMatchFound(MatchFoundPacket note) {
                        pendingMatch.set(note);
                        status.set("Match found - starting soon.");
                        matchCountdownSec.set(note.countdownSeconds());
                    }

                    @Override
                    public void onMatchCountdown(int secondsRemain) {
                        matchCountdownSec.set(secondsRemain);
                    }

                    @Override
                    public void onMatchStart(MatchStartPacket kickoff) {
                        MatchFoundPacket mf = pendingMatch.get();
                        if (mf == null) {
                            return;
                        }
                        matchCountdownSec.set(-1);
                        com.badlogic.gdx.Gdx.app.postRunnable(() -> {
                            udp.ignite(mf, kickoff);
                            setScreen(new GameScreen(LobbyGame.this, mf));
                        });
                    }

                    @Override
                    public void onDisconnectedUnexpectedly() {
                        pendingMatch.set(null);
                        matchCountdownSec.set(-1);
                        status.set("Disconnected");
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
        pendingMatch.set(null);
        matchCountdownSec.set(-1);
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
