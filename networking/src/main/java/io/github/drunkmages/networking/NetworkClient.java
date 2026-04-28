package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;

import io.github.drunkmages.common.Handshake;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;

// Throwaway client used while we don't have a real lobby UI yet.
// Wire format mirrors NetworkServer: u16 length + UTF-8 nickname, reply is i32 id.
public final class NetworkClient {

    public static void connectAndGreet(String host, int port, String nickname) throws Exception {
        Handshake handshake = new Handshake(nickname);
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = Tcp.client(group);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
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
                            ByteBuf in = (ByteBuf) msg;
                            try {
                                // server only sends one int back, then we are done
                                if (in.readableBytes() >= 4) {
                                    int id = in.readInt();
                                    System.out.println("welcome id=" + id);
                                    ctx.close();
                                }
                            } finally {
                                in.release();
                            }
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
            System.out.println("done");
        } finally {
            group.shutdownGracefully();
        }
    }

    private NetworkClient() {
    }
}
