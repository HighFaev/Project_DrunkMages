package io.github.drunkmages.networking;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

// Tiny factory so both sides agree on the same channel type.
// If we ever swap NIO for epoll, only this file changes.
public final class Tcp {

    private Tcp() {
    }

    public static ServerBootstrap server(EventLoopGroup boss, EventLoopGroup workers) {
        return new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class);
    }

    public static Bootstrap client(EventLoopGroup group) {
        return new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class);
    }
}
