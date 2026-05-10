package io.github.drunkmages.networking;

public record PlayerDiedTcpPacket(int playerId, int killerId, String killerNickname, int placement) {
}
