package io.github.drunkmages.networking;

import java.util.List;

public record MatchEndPacket(long matchId, int winnerId, String winnerNickname, int durationTicks,
        List<MatchStatEntry> stats) {
}
