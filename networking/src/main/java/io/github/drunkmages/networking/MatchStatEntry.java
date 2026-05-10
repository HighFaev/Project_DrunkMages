package io.github.drunkmages.networking;

public record MatchStatEntry(int playerId, int placement, int kills, int damageDealt, long survivalTicks) {
}
