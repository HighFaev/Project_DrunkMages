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

        default void onMatchEnd(MatchEndPacket pack) {}
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
    public record SnapshotItem(int entityId, float x, float y, int itemType) {}
    public record SnapshotPlayer(int entityId, float x, float y, float aimRadians, int hp, int maxHp,int selectedSlot, int anim, int[] inventory) {}


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

        public record ZoneStateUdpPacket(float curX, float curY, float curRadius, float nextX, float nextY, float nextRadius, int shrinkStartTick, int shrinkEndTick, int damagePerTick, int phase) { }
        private static final AtomicReference<ZoneStateUdpPacket> lastZone = new AtomicReference<>();
        public ZoneStateUdpPacket zonePeek() { return lastZone.get(); }

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
                                                if (!nectar.isReadable(UdpOpcodes.HEADER_BYTES)) return;
                                                byte type = nectar.getByte(nectar.readerIndex());

                                                if (type == UdpOpcodes.S_WORLD_SNAPSHOT) {
                                                    decodeWorldSnapshot(nectar);
                                                } else if (type == UdpOpcodes.S_ZONE_STATE) {
                                                    decodeZoneState(nectar); // ADD THIS
                                                }

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

        private void decodeZoneState(ByteBuf raw) {
            raw.markReaderIndex();
            try {
                // 6 floats (24 bytes) + 2 ints (8 bytes) + 1 short (2 bytes) + 1 byte (1 byte) = 35 bytes
                if (!raw.isReadable(UdpOpcodes.HEADER_BYTES + 35)) return;
                UdpHeader.read(raw);
                float cx = raw.readFloat(); float cy = raw.readFloat(); float cr = raw.readFloat();
                float nx = raw.readFloat(); float ny = raw.readFloat(); float nr = raw.readFloat();
                int startTick = raw.readInt();
                int endTick = raw.readInt();
                int damage = raw.readUnsignedShort();
                int phase = raw.readUnsignedByte();
                lastZone.set(new ZoneStateUdpPacket(cx, cy, cr, nx, ny, nr, startTick, endTick, damage, phase));
            } catch (Exception e) {
                raw.resetReaderIndex();
            }
        }
        public void sendRespawnRequest() {
            Channel z = cannon_;
            InetSocketAddress bull = aim_;
            if (!active.get() || z == null || !z.isActive() || bull == null) {
                return;
            }
            seqOutbound_++;
            ByteBuf buf = z.alloc().buffer(UdpOpcodes.HEADER_BYTES);
            // Hijack C_RELOAD to act as the respawn signal
            UdpHeader.write(buf, UdpOpcodes.C_RELOAD, seqOutbound_, 0, warriorSlot_, matchXor_);
            z.writeAndFlush(new DatagramPacket(buf, bull));
        }

        /**
         * §5 WORLD_SNAPSHOT — header, HUD fields, and MVP player positions (matches {@code WorldSnapshotCodec}).
         */

        private final AtomicReference<List<SnapshotItem>> lastItems = new AtomicReference<>(List.of());
        public List<SnapshotItem> snapshotItemsPeek() { return lastItems.get(); }

        public void sendPickupRequest() {
            Channel z = cannon_; InetSocketAddress bull = aim_;
            if (!active.get() || z == null || !z.isActive() || bull == null) return;
            seqOutbound_++;
            ByteBuf buf = z.alloc().buffer(UdpOpcodes.HEADER_BYTES);
            UdpHeader.write(buf, UdpOpcodes.C_ITEM_PICKUP, seqOutbound_, 0, warriorSlot_, matchXor_);
            z.writeAndFlush(new DatagramPacket(buf, bull));
        }


        public void sendDropRequest(int slot) {
            Channel z = cannon_; InetSocketAddress bull = aim_;
            if (!active.get() || z == null || !z.isActive() || bull == null) return;
            seqOutbound_++;
            ByteBuf buf = z.alloc().buffer(UdpOpcodes.HEADER_BYTES + 2);
            UdpHeader.write(buf, UdpOpcodes.C_ITEM_DROP, seqOutbound_, 0, warriorSlot_, matchXor_);
            buf.writeByte(slot);
            buf.writeByte(0); // 0 = drop all of stack
            z.writeAndFlush(new DatagramPacket(buf, bull));
        }

        public void sendReloadRequest(int weaponSlot) {
            Channel z = cannon_; InetSocketAddress bull = aim_;
            if (!active.get() || z == null || !z.isActive() || bull == null) return;
            seqOutbound_++;
            ByteBuf buf = z.alloc().buffer(UdpOpcodes.HEADER_BYTES + 1);
            UdpHeader.write(buf, UdpOpcodes.C_RELOAD, seqOutbound_, 0, warriorSlot_, matchXor_);
            buf.writeByte(weaponSlot);
            z.writeAndFlush(new DatagramPacket(buf, bull));
        }

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

                // FIX: Actually save the server tick so the HUD timer updates!
                lastPeek.set(new UdpHud(head.seqUnsigned(), head.tickUnsigned(), entityBurst));

                ArrayList<SnapshotPlayer> accP = new ArrayList<>();
                ArrayList<SnapshotItem> accI = new ArrayList<>();
                for (int i = 0; i < entityBurst; i++) {
                    if (!raw.isReadable(3)) break;
                    int entityId = raw.readUnsignedShort();
                    byte entityType = raw.readByte();

                    if (entityType == UdpOpcodes.ENTITY_PLAYER) {
                        // FIX: Increased from 27 to 32 bytes to account for 16-bit inventory slots
                        if (!raw.isReadable(32)) break;
                        float x = raw.readFloat(); float y = raw.readFloat();
                        float aim = raw.readFloat(); raw.skipBytes(1);
                        int hp = raw.readUnsignedShort(); int maxHp = raw.readUnsignedShort();
                        int selectedSlot = raw.readUnsignedByte();
                        int anim = raw.readUnsignedByte();
                        int[] inv = new int[5];
                        for (int k = 0; k < 5; k++) {
                            // FIX: Read UnsignedShort to retrieve rarity data
                            inv[k] = raw.readUnsignedShort();
                        }
                        raw.skipBytes(3);// pad
                        accP.add(new SnapshotPlayer(entityId, x, y, aim, hp, maxHp, selectedSlot, anim, inv));
                    } else if (entityType == UdpOpcodes.ENTITY_ITEM_ON_GROUND) {
                        if (!raw.isReadable(11)) break;
                        float ix = raw.readFloat(); float iy = raw.readFloat();
                        int iType = raw.readUnsignedShort(); raw.skipBytes(1); // qty
                        accI.add(new SnapshotItem(entityId, ix, iy, iType));
                    } else {
                        break; // Unknown entity, break to avoid desync
                    }
                }
                lastPlayers.set(List.copyOf(accP));
                lastItems.set(List.copyOf(accI));


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


        public void sendSwitchWeaponRequest(int slot) {
            Channel z = cannon_; InetSocketAddress bull = aim_;
            if (!active.get() || z == null || !z.isActive() || bull == null) return;
            seqOutbound_++;
            ByteBuf buf = z.alloc().buffer(UdpOpcodes.HEADER_BYTES + 1);
            UdpHeader.write(buf, UdpOpcodes.C_ITEM_USE, seqOutbound_, 0, warriorSlot_, matchXor_);
            buf.writeByte(slot);
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


            if (frame instanceof MatchEndPacket pack) {
                ear_.onMatchEnd(pack);
                return;
            }

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
