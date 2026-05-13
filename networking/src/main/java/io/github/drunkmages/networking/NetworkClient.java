package io.github.drunkmages.networking;

import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.common.RosterUpdate;
import io.github.drunkmages.common.net.udp.UdpHeader;
import io.github.drunkmages.common.net.udp.UdpOpcodes;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * Non-blocking-Netty façade: TCP lobby (§4) plus optional UDP game transport (§5).
 */
public final class NetworkClient {

    public interface LobbyListener {


        default void onWelcome(int lobbyAssignedId) {


        }


        default void onRoster(List<PlayerInfo> everybody) {


        }

        default void onPlayerDied(PlayerDiedTcpPacket note) {}

        default void onMatchFound(MatchFoundPacket note) {


        }


        default void onMatchCountdown(int secondsRemain) {


        }


        default void onMatchStart(MatchStartPacket kickoff) {


        }


        default void onDisconnectedUnexpectedly() {


        }


    }


    /**
     * Legacy lobby callbacks used by older app codepaths; prefer {@link LobbyListener}.
     */
    public interface Listener {


        void onWelcome(int id);


        void onRosterUpdate(List<PlayerInfo> players);


        void onDisconnected();


    }


    /**
     * @deprecated Prefer {@link #connectBlocking} with {@link LobbyListener}.
     */
    @Deprecated
    public void connect(String remoteHost,

                        int tcpListen,

                        String nickname,

                        Listener legacyPlug) throws Exception {


        connectBlocking(
                remoteHost,
                tcpListen,
                nickname,
                new LobbyListener() {


                    @Override
                    public void onWelcome(int lobbyAssignedId) {


                        legacyPlug.onWelcome(lobbyAssignedId);


                    }


                    @Override
                    public void onRoster(List<PlayerInfo> everybody) {


                        legacyPlug.onRosterUpdate(everybody);


                    }


                    @Override
                    public void onDisconnectedUnexpectedly() {


                        legacyPlug.onDisconnected();


                    }


                });


    }


    /**
     * @deprecated Prefer {@link #slamShutdown()}.
     */
    @Deprecated
    public void disconnect() {


        slamShutdown();


    }


    public record UdpHud(int serverSeq, int serverTick, int entityCount) {


    }


    /** One player row from WORLD_SNAPSHOT (MVP layout from {@code WorldSnapshotCodec}). */
    public record SnapshotPlayer(int entityId, float x, float y, float aimRadians, int hp, int maxHp, int anim) {


    }


    /**
     * Aim + WASD bitmask for UDP INPUT (see {@code MatchRuntime.applyMotion}: bits 0–3 = up, down, left, right).
     */
    public record DriveSlice(float aimRadians, int moveButtonsMask) {


        public static final DriveSlice NEUTRAL = new DriveSlice(0f, 0);


    }


    private volatile EventLoopGroup netCluster_;

    private volatile Channel tcpControl_;

    private volatile GameUdpClient udpCompanion_;

    public void attachUdpBuddy(GameUdpClient companion) {


        this.udpCompanion_ = companion;

    }


    /** Keeps TLS-less TCP alive until teardown. */


    public void connectBlocking(String remoteHost,

                                int tcpListen,

                                String nickname,

                                LobbyListener listenerPlug) throws Exception {


        Objects.requireNonNull(remoteHost);


        Objects.requireNonNull(nickname);


        Objects.requireNonNull(listenerPlug);


        EventLoopGroup pool =


                new NioEventLoopGroup(1);


        GameUdpClient friend = udpCompanion_;

        netCluster_ = pool;

        try {


            Bootstrap scaffold = Tcp.client(pool);


            ChannelFuture tether =
                    scaffold.handler(
                                    new ChannelInitializer<SocketChannel>() {


                                        @Override


                                        protected void initChannel(SocketChannel ch) {


                                            ch.pipeline()


                                                    .addLast(new TcpFromServerDecoder())


                                                    .addLast(
                                                            new LobbyIoTail(
                                                                    nickname,

                                                                    listenerPlug,

                                                                    friend));

                                        }


                                    })


                            .connect(remoteHost, tcpListen);

            tcpControl_ = tether.sync().channel();


            tcpControl_.closeFuture().await();


        }


        finally {


            tcpControl_ = null;


            netCluster_ = null;

            udpCompanion_ =


                    null;

            pool.shutdownGracefully();

            if (friend != null) {


                friend.stop();

            }


        }


    }


    public void sendPlayerReadyPulse() {


        Channel up = tcpControl_;

        if (up == null || !up.isActive()) {


            return;


        }


        io.netty.channel.EventLoop loop = up.eventLoop();

        if (loop.inEventLoop()) {


            up.writeAndFlush(LobbyTcpOutbound.playerReady(up.alloc()));

        } else {


            loop.execute(() -> {

                if (up.isActive()) {


                    up.writeAndFlush(LobbyTcpOutbound.playerReady(up.alloc()));

                }


            });

        }


    }


    public void slamShutdown() {


        Channel up = tcpControl_;

        tcpControl_ =


                null;

        GameUdpClient g = udpCompanion_;

        udpCompanion_ = null;


        if (g !=


                null) {


            g.stop();

        }


        if (up != null) {


            up.close();


        }


        EventLoopGroup gPool = netCluster_;

        netCluster_ = null;

        if (gPool != null) {


            gPool.shutdownGracefully();

        }


    }


    public static final class GameUdpClient {


        private volatile EventLoopGroup lane_;

        private volatile Channel cannon_;

        private volatile InetSocketAddress aim_;

        private volatile long matchXor_;

        private volatile int warriorSlot_; // u16 truncated

        private volatile ScheduledFuture<?> cadence_;

        private final AtomicBoolean active =


                new AtomicBoolean(false);


        private final AtomicReference<UdpHud> lastPeek =


                new AtomicReference<>();

        private final AtomicReference<List<SnapshotPlayer>> lastPlayers = new AtomicReference<>(List.of());

        private final AtomicReference<DriveSlice> pendingDrive = new AtomicReference<>(DriveSlice.NEUTRAL);


        /** Bytes per entity blob in {@code WorldSnapshotCodec.encode} for ENTITY_PLAYER. */
        private static final int SNAPSHOT_PLAYER_ENTITY_BYTES = 30;


        private int seqOutbound_;

        private int inputRing_;


        public void ignite(MatchFoundPacket lease,

                           MatchStartPacket __kickoffIgnored) {


            stop();

            lane_ =
                    new NioEventLoopGroup(1);

            warriorSlot_ =
                    lease.localMatchPlayerId() &


                    0xffff;

            matchXor_ = lease.matchId();

            aim_ = new InetSocketAddress(lease.udpHost(), lease.udpPort());


            try {


                Bootstrap b =


                        new Bootstrap()


                                .group(lane_)


                                .channel(NioDatagramChannel.class)


                                .handler(
                                        new SimpleChannelInboundHandler<DatagramPacket>() {


                                            @Override


                                            protected void channelRead0(ChannelHandlerContext cx,

                                                                        DatagramPacket pkt) {


                                                ByteBuf nectar = pkt.content();


                                                decodeWorldSnapshot(nectar);

                                            }


                                        });


                cannon_ =
                        b.bind(0).sync().channel();


                seqOutbound_ =
                        1;


                active.set(true);

                lastPlayers.set(List.of());

                pendingDrive.set(DriveSlice.NEUTRAL);

                cadence_ =
                        cannon_.eventLoop()
                                .scheduleAtFixedRate(GameUdpClient.this::emitInputSlice,
                                        40,
                                        40,
                                        TimeUnit.MILLISECONDS);


            }


            catch (Exception ex) {


                ex.printStackTrace();

                stop();

            }


        }


        /**
         * §5 WORLD_SNAPSHOT — header, HUD fields, and MVP player positions (matches {@code WorldSnapshotCodec}).
         */


        private void decodeWorldSnapshot(ByteBuf raw) {


            raw.markReaderIndex();

            try {


                if (!raw.isReadable(UdpOpcodes.HEADER_BYTES + 2)) {


                    return;


                }


                UdpHeader head = UdpHeader.read(raw);

                if (head.type() != UdpOpcodes.S_WORLD_SNAPSHOT) {


                    raw.resetReaderIndex();

                    return;


                }


                raw.readUnsignedByte();

                int entityBurst = raw.readUnsignedByte();

                UdpHud hud = new UdpHud(head.seqUnsigned(), head.tickUnsigned(), entityBurst);

                ArrayList<SnapshotPlayer> acc = new ArrayList<>(Math.min(entityBurst, 64));

                for (int i = 0; i < entityBurst; i++) {


                    if (!raw.isReadable(SNAPSHOT_PLAYER_ENTITY_BYTES)) {


                        raw.resetReaderIndex();

                        return;


                    }


                    int entityId = raw.readUnsignedShort();

                    byte entityType = raw.readByte();

                    float x = raw.readFloat();

                    float y = raw.readFloat();

                    float aim = raw.readFloat();

                    raw.skipBytes(1);

                    int hp = raw.readUnsignedShort();

                    int maxHp = raw.readUnsignedShort();

                    raw.skipBytes(4); // shield (2) + max shield (2)
                    raw.skipBytes(1); // held slot
                    int anim = raw.readUnsignedByte(); // Extract animation!
                    raw.skipBytes(4); // inv0, inv1, ammo_p, ammo_s

                    if (entityType == UdpOpcodes.ENTITY_PLAYER) {


                        acc.add(new SnapshotPlayer(entityId, x, y, aim, hp, maxHp, anim));


                    }


                }


                lastPeek.set(hud);

                lastPlayers.set(List.copyOf(acc));


            }


            catch (RuntimeException ignored) {


                raw.resetReaderIndex();


            }


        }


        /** Latest parsed players from WORLD_SNAPSHOT (immutable list; possibly empty). */


        public List<SnapshotPlayer> snapshotPlayersPeek() {


            return lastPlayers.get();


        }


        /** Called from the game render thread: aim + WASD bitmask (bits 0–3). */


        public void setDriveInput(float aimRadians, int moveButtonsMask) {


            pendingDrive.set(new DriveSlice(aimRadians, moveButtonsMask & 0xff));


        }


        /** §5 INPUT — velocity slots unused when buttons drive movement on server. */


        private void emitInputSlice() {


            Channel z = cannon_;

            InetSocketAddress bull = aim_;

            if (!active.get() ||


                    z ==


                            null || !z.isActive() || bull ==


                    null) {


                return;


            }


            seqOutbound_++;

            inputRing_ =


                    (inputRing_ +
                            1) &


                            0xFF;


            DriveSlice slice = pendingDrive.get();

            ByteBuf buf =


                    z.alloc()


                            .buffer(UdpOpcodes.HEADER_BYTES +


                                    22);


            UdpHeader.write(buf,

                    UdpOpcodes.C_INPUT, seqOutbound_,
                    0,
                    warriorSlot_, matchXor_);


            /* predicted XY ignored */ buf.writeFloat(

                    0);


            buf.writeFloat(


                    0);

            /*
             * velocity XY + aim radians
             */
            buf.writeFloat(


                    0);


            buf.writeFloat(


                    0);


            buf.writeFloat(


                    slice.aimRadians());


            /*
             * buttons + input_sequence
             */
            buf.writeByte(


                    slice.moveButtonsMask());


            buf.writeByte(


                    inputRing_);


            z.writeAndFlush(new DatagramPacket(buf, bull));


        }


        /** Tear down selectors + outbound cadence safely. */


        public void stop() {


            active.set(false);

            lastPeek.set(null);

            lastPlayers.set(List.of());

            pendingDrive.set(DriveSlice.NEUTRAL);

            ScheduledFuture<?> ticker = cadence_;

            cadence_ =


                    null;

            Channel c = cannon_;

            cannon_ = null;

            lane_Shutdown();


            tickerSafeCancel(ticker);

            cannonSafeClose(c);

        }


        private void cannonSafeClose(Channel cannon) {


            if (cannon !=


                    null


                    &&
                    cannon.isOpen()) {


                cannon.close();


            }


        }


        private void tickerSafeCancel(ScheduledFuture<?> tic) {


            if (tic != null) {


                tic.cancel(false);

            }


        }


        private void lane_Shutdown() {


            EventLoopGroup row = lane_;

            lane_ = null;

            if (row !=


                    null) {


                row.shutdownGracefully();

            }


        }


        /** Latest WORLD_SNAPSHOT summary (possibly {@code null}). */


        public UdpHud snapshotPeek() {


            return lastPeek.get();

        }


    }


    /** Multiplex inbound TCP payloads into thin {@link LobbyListener} façade. */


    static final class LobbyIoTail extends SimpleChannelInboundHandler<Object> {


        private final String nickChosen_;

        private final LobbyListener ear_;

        private final GameUdpClient udpFriend_;

        LobbyIoTail(String nick, LobbyListener ear, GameUdpClient udpFriend) {


            nickChosen_ =
                    nick;

            ear_ =
                    ear;

            udpFriend_ =
                    udpFriend;


        }


        @Override


        public void channelActive(ChannelHandlerContext ctxBegin) {


            ctxBegin.writeAndFlush(LobbyTcpOutbound.handshake(ctxBegin.alloc(), nickChosen_));


        }


        @Override


        protected void channelRead0(ChannelHandlerContext ctxDrain, Object frame) {


            if (frame instanceof WelcomePacket greeting) {


                ear_.onWelcome(greeting.id());


                return;


            }

            if (frame instanceof PlayerDiedTcpPacket pd) {
                ear_.onPlayerDied(pd);
                return;
            }


            if (frame instanceof RosterUpdate patch) {


                ear_.onRoster(patch.players());


                return;


            }


            if (frame instanceof MatchFoundPacket pack) {


                ear_.onMatchFound(pack);

                return;


            }


            if (frame instanceof MatchCountdownPacket cd) {


                ear_.onMatchCountdown(cd.secondsLeft());

                return;


            }


            if (frame instanceof MatchStartPacket go) {


                ear_.onMatchStart(go);


                return;


            }


        }


        @Override


        public void channelInactive(ChannelHandlerContext ctxDie) {


            if (udpFriend_ !=


                    null) {


                udpFriend_.stop();


            }


            ear_.onDisconnectedUnexpectedly();


        }


        @Override


        public void exceptionCaught(ChannelHandlerContext ctxErr, Throwable err) {


            err.printStackTrace();

            ctxErr.close();


        }


    }


}
