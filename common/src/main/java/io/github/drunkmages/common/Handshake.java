package io.github.drunkmages.common;

import java.util.Objects;

// First packet the client sends after the TCP connect.
// Keep it dumb and tiny - we only need the nickname for now.
public final class Handshake {

    private final String nickname;

    public Handshake(String nickname) {
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        if (nickname.isBlank()) {
            // blank nicks would later collide in the lobby roster
            throw new IllegalArgumentException("nickname blank");
        }
    }

    public String nickname() {
        return nickname;
    }
}
