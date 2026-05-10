package io.github.drunkmages.networking.game;

import java.net.InetAddress;

public record MatchParticipant(int matchLocalPlayerId, float spawnX, float spawnY, InetAddress tcpRemoteIp) {
}
