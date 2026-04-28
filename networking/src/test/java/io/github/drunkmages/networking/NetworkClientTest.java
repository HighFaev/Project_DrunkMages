package io.github.drunkmages.networking;

public final class NetworkClientTest {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String nick = args.length > 2 ? args[2] : "player";
        NetworkClient.connectAndGreet(host, port, nick);
    }

    private NetworkClientTest() {
    }
}
