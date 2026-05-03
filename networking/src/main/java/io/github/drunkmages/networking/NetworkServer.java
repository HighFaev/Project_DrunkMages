package io.github.drunkmages.networking;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.DuplicateFormatFlagsException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.drunkmages.common.Handshake;
import io.github.drunkmages.common.PlayerInfo;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.GlobalEventExecutor;

/**
 * Dedicated server entry point.
 *
 * Wire format (server → client):
 * <pre>
 *   WELCOME  0x01  [i32 id]
 *   ROSTER   0x02  [i32 count] ( [i32 id] [u16 nameLen] [utf8 name] ) * count
 * </pre>
 *
 * A ROSTER broadcast is sent to every connected client whenever a player
 * joins or leaves.
 */
public final class NetworkServer {

    private static final AtomicInteger IDS = new AtomicInteger(1);
    /** All live channels — DefaultChannelGroup auto-removes closed channels. */
    private static final ChannelGroup ALL = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    /** channelId → PlayerInfo for every player that has completed the handshake. */
    private static final ConcurrentHashMap<ChannelId, PlayerInfo> PLAYERS = new ConcurrentHashMap<>();

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


    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Encodes and broadcasts the current player list to every connected client. */
    private static void broadcastRoster() {
        List<PlayerInfo> snapshot = List.copyOf(PLAYERS.values());

        List<byte[]> names = new ArrayList<>(snapshot.size());
        int bufSize = 1 /* type */ + 4 /* count */;
        for (PlayerInfo p: snapshot) {
            byte[] nb = p.nickname().getBytes(StandardCharsets.UTF_8);
            names.add(nb);
            bufSize += 4 /* id */ + 2 /* nameLen */ + nb.length;
        }

        ByteBuf buf = Unpooled.buffer(bufSize);
        buf.writeByte(0x02);
        buf.writeByte(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            buf.writeInt(snapshot.get(i).id());
            buf.writeShort(names.get(i).length);
            buf.writeBytes(names.get(i));
        }
        ALL.writeAndFlush(buf);
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

        private volatile ChannelId channelId;


        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Handshake handshake) {
            int id = IDS.getAndIncrement();
            channelId = ctx.channel().id();
            PLAYERS.put(channelId, new PlayerInfo(id, handshake.nickname()));
            ALL.add(ctx.channel());

            System.out.println("joined " + handshake.nickname() + " as " + id);
            // welcome
            ByteBuf response = ctx.alloc().buffer(5);
            response.writeByte(0x01);
            response.writeInt(id);
            ctx.writeAndFlush(response);

            broadcastRoster();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (channelId != null) {
                PlayerInfo left = PLAYERS.remove(channelId);
                // ALL auto-removes the closed channel; no manual remove needed.
                if (left != null) {
                    System.out.println("left " + left.nickname() + " (id=" + left.id() + ")");
                    broadcastRoster();
                }
            }
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
