package io.github.drunkmages.networking;

public record PlayerDiedTcpPacket(int playerId, String victimNickname, int killerId, String killerNickname, int placement) {
}
