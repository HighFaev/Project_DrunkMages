package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;

import io.github.drunkmages.common.Handshake;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

import java.util.List;

/**
 * Connects to the game server, sends the handshake, and then stays open to
 * receive WELCOME and ROSTER packets.  Callers are notified via {@link Listener}.
 *
 * <p>Intended to be run on a background thread; {@link #connect} blocks until
 * the connection is closed.
 */
public final class NetworkClient {

    public interface Listener {
        /** Called once, after the server sends back our assigned id. */
        void onWelcome(int myId);
        /** Called every time the server broadcasts a fresh roster (join / leave). */
        void onRosterUpdate(List<PlayerInfo> players);
        /** Called when the channel closes for any reason. */
        void onDisconnected();
    }

    public static void connect(String host, int port, String nickname, Listener listener) throws Exception {
        Handshake handshake = new Handshake(nickname);
        EventLoopGroup group = new NioEventLoopGroup();
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
                            byte[] raw = handshake.nickname().getBytes(StandardCharsets.UTF_8);
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
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            cause.printStackTrace();
                            ctx.close();
                        }
                    });
                }
            });
            ChannelFuture future = bootstrap.connect(host, port).sync();
            System.out.println("connected " + host + ":" + port + " nick=" + handshake.nickname());
            // wait until welcome handler closes us
            future.channel().closeFuture().sync();
            System.out.println("disconected");
        } finally {
            group.shutdownGracefully();
        }
    }

    private NetworkClient() {
    }
}
