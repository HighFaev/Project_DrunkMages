package io.github.drunkmages;

import com.badlogic.gdx.Game;
import io.github.drunkmages.common.PlayerInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class LobbyGame extends Game {
    private final AtomicInteger myId;
    private final AtomicReference<List<PlayerInfo>> roster;
    private final AtomicReference<String> status;

    public LobbyGame(AtomicInteger myId,
                     AtomicReference<List<PlayerInfo>> roster,
                     AtomicReference<String> status) {
        this.myId   = myId;
        this.roster = roster;
        this.status = status;
    }

    @Override
    public void create() {
        setScreen(new LobbyScreen(myId, roster, status));
    }
}
