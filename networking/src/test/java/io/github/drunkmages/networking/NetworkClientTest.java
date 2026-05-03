package io.github.drunkmages.networking;

import io.github.drunkmages.common.PlayerInfo;

import java.util.List;

public final class NetworkClientTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String nick = args.length > 2 ? args[2] : "player";
        NetworkClient.connect(host, port, nick, new NetworkClient.Listener() {
            @Override
            public void onWelcome(int myId) {
                System.out.println("welcome id=" + myId);
            }

            @Override
            public void onRosterUpdate(List<PlayerInfo> players) {
                System.out.println("roster: " + players);
            }

            @Override
            public void onDisconnected() {
                System.out.println("done");
            }
        });
    }

    private NetworkClientTest() {
    }
}
