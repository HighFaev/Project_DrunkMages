package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.drunkmages.common.Handshake;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;

// Standalone entry point for the dedicated server.
// Single-purpose for now: accept a connection, take a nickname, hand out an id.
public final class NetworkServer {

    private static final AtomicInteger IDS = new AtomicInteger(1);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 25565;
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = Tcp.server(boss, workers);
            bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HandshakeDecoder());
                    ch.pipeline().addLast(new HandshakeHandler());
                }
            });
            ChannelFuture bind = bootstrap.bind(port).sync();
            System.out.println("server listening on " + port);
            bind.channel().closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            workers.shutdownGracefully();
        }
    }

    // u16 length + UTF-8 nickname.
    // TODO: swap for a real packet codec when we add the second packet type.
    private static final class HandshakeDecoder extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 2) {
                return;
            }
            in.markReaderIndex();
            int length = in.readUnsignedShort();
            if (in.readableBytes() < length) {
                // payload not fully here yet, rewind and wait
                in.resetReaderIndex();
                return;
            }
            byte[] raw = new byte[length];
            in.readBytes(raw);
            out.add(new Handshake(new String(raw, StandardCharsets.UTF_8)));
        }
    }

    private static final class HandshakeHandler extends SimpleChannelInboundHandler<Handshake> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Handshake handshake) {
            int id = IDS.getAndIncrement();
            System.out.println("joined " + handshake.nickname() + " as " + id);
            ByteBuf response = ctx.alloc().buffer(4);
            response.writeInt(id);
            ctx.writeAndFlush(response);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }

    private NetworkServer() {
    }
}
