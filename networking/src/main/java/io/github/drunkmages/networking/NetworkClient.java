package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.github.drunkmages.common.Handshake;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

/**
 * Game server client.  Create one instance, call {@link #connect} on a
 * background thread (it blocks until the connection closes), and call
 * {@link #disconnect} from any thread to tear it down — e.g. when the
 * game window is closed.
 *
 * IMPORTANT: connect() must be an *instance* method so that it can write
 * to this.channel / this.group, which disconnect() reads.  A static method
 * cannot do that — that was the previous bug causing disconnect() to be a no-op.
 */
public final class NetworkClient {

    public interface Listener {
        void onWelcome(int myId);
        void onRosterUpdate(List<PlayerInfo> players);
        void onDisconnected();
    }

    // Written once by the connect() thread; read by disconnect() from any thread.
    private volatile Channel        channel;
    private volatile EventLoopGroup group;

    /**
     * Opens a TCP connection, sends the handshake, then blocks until the
     * connection closes (either by the server or by {@link #disconnect}).
     * Call this on a background thread.
     */
    public void connect(String host, int port, String nickname,
                        Listener listener) throws Exception {
        Handshake handshake = new Handshake(nickname);

        // Assign to the instance field BEFORE connecting so disconnect() can
        // reach the group even if the channel hasn't been assigned yet.
        group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = Tcp.client(group);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                            .addLast(new ServerPacketDecoder())
                            .addLast(new ChannelInboundHandlerAdapter() {

                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    byte[] raw = handshake.nickname()
                                            .getBytes(StandardCharsets.UTF_8);
                                    ByteBuf buf = ctx.alloc().buffer(2 + raw.length);
                                    buf.writeShort(raw.length);
                                    buf.writeBytes(raw);
                                    ctx.writeAndFlush(buf);
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    if (msg instanceof WelcomePacket w) {
                                        listener.onWelcome(w.id());
                                    } else if (msg instanceof RosterUpdate r) {
                                        listener.onRosterUpdate(r.players());
                                    }
                                }

                                @Override
                                public void channelInactive(ChannelHandlerContext ctx) {
                                    listener.onDisconnected();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx,
                                                            Throwable cause) {
                                    cause.printStackTrace();
                                    ctx.close();
                                }
                            });
                }
            });

            ChannelFuture future = bootstrap.connect(host, port).sync();
            channel = future.channel(); // now disconnect() can close it
            System.out.println("connected " + host + ":" + port
                    + " nick=" + handshake.nickname());
            channel.closeFuture().sync();
            System.out.println("disconnected");
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * Closes the channel and shuts down Netty's event loop.
     * Safe to call from any thread. Returns immediately; teardown is async.
     */
    public void disconnect() {
        Channel ch = channel;
        if (ch != null) ch.close();
        EventLoopGroup g = group;
        if (g != null) g.shutdownGracefully();
    }
}