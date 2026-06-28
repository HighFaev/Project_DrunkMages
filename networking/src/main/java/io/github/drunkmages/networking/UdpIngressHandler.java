package io.github.drunkmages.networking;

import io.github.drunkmages.networking.game.MatchRuntime;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

import java.util.function.Supplier;

final class UdpIngressHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final Supplier<MatchRuntime> activeRuntime;

    UdpIngressHandler(Supplier<MatchRuntime> activeRuntime) {

        super(false);

        this.activeRuntime = activeRuntime;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {


        MatchRuntime rt = activeRuntime.get();

        if (rt == null) {
            return;
        }

        ByteBuf slice = msg.content().retainedDuplicate();

        rt.ingest(msg.sender(), slice);
    }
}
