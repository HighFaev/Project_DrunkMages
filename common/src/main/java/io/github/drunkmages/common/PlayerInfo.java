package io.github.drunkmages.common;


/** Snapshot of one connected player sent inside every roster broadcast. */
public record PlayerInfo(int id, String nickname, boolean lobbyReady) {}
