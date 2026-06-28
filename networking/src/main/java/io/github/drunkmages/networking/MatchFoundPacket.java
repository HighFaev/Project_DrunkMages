package io.github.drunkmages.networking;

public record MatchFoundPacket(long matchId, String udpHost, int udpPort, int localMatchPlayerId,
        int playerCount, float spawnX, float spawnY, int countdownSeconds) {
}
