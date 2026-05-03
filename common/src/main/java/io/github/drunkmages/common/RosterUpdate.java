package io.github.drunkmages.common;

import java.util.List;

/**
 * Server → client broadcast: the full, current list of connected players.
 * Sent whenever someone joins or leaves.
 */
public record RosterUpdate(List<PlayerInfo> players) {}