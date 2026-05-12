package io.github.drunkmages.networking;

import io.github.drunkmages.common.Handshake;
import io.github.drunkmages.common.PlayerInfo;
import io.github.drunkmages.networking.game.MatchParticipant;
import io.github.drunkmages.networking.game.MatchRuntime;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * TCP lobby + UDP game-plane in one JVM. Defaults: TCP {@code 25565}, UDP {@code 25566}.
 */
public final class NetworkServer {

    static final int MIN_PLAYERS =
            Integer.getInteger("javaroyale.lobby.minPlayers", 1);

    static final int MATCH_COUNTDOWN =
            Integer.getInteger("javaroyale.match.countdown", 5);

    private static final AtomicInteger GLOBAL_LOBBY_IDS = new AtomicInteger(1);

    /** First issued match identifier is {@code 1}. */


    private static final AtomicInteger MATCH_IDS = new AtomicInteger(0);

    private static final ConcurrentHashMap<String, Boolean> NICK_BOOK =
            new ConcurrentHashMap<>();

    private static final ChannelGroup ONLINE = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private static final ConcurrentHashMap<ChannelId, LobbyPeer> SESSIONS =
            new ConcurrentHashMap<>();

    private static DatagramChannel UDP;

    /** Current arena instance ( UDP thread only after creation ). */

    static final AtomicReference<MatchRuntime> ARENA_REF = new AtomicReference<>();

    private static volatile ScheduledFuture<?> SIM_LOOP;

    private static volatile boolean COUNTDOWN_RUNNING;


    private static volatile int LOBBY_EPOCH;


    private static String UDP_ADVERT_HOST;

    public static void main(String[] args) throws Exception {

        final int tcpPort = args.length > 0 ? Integer.parseInt(args[0]) : 25565;

        final int udpPort = args.length > 1 ? Integer.parseInt(args[1]) : 25566;

        UDP_ADVERT_HOST =
                System.getProperty(
                        "javaroyale.advertise.host", InetAddress.getLoopbackAddress().getHostAddress());

        var boss = new NioEventLoopGroup(1);

        var workers = new NioEventLoopGroup();

        try {

            Bootstrap udpBootstrap =
                    new Bootstrap()
                            .group(workers)
                            .channel(NioDatagramChannel.class)
                            .handler(
                                    new ChannelInitializer<>() {


                                        @Override
                                        protected void initChannel(io.netty.channel.Channel child) {


                                            child.pipeline()

                                                    .addLast(new UdpIngressHandler(ARENA_REF::get));


                                        }


                                    });


            UDP = (DatagramChannel) udpBootstrap.bind(udpPort).sync().channel();

            ServerBootstrap tcp = Tcp.server(boss, workers);


            var tcpListening =
                    tcp.childHandler(
                                    new ChannelInitializer<SocketChannel>() {


                                        @Override
                                        protected void initChannel(SocketChannel ch) {


                                            ch.pipeline()


                                                    .addLast(new TcpFromClientDecoder())


                                                    .addLast(new LobbyHandler());

                                        }


                                    })


                            .bind(tcpPort)


                            .sync()


                            .channel();


            System.out.println("javaroyale TCP lobby :" + tcpPort);


            System.out.println("javaroyale UDP arena  :" + udpPort);


            tcpListening.closeFuture().sync();

        }


        finally {

            stopTicker();

            if (UDP != null) {


                UDP.close().awaitUninterruptibly();

            }

            boss.shutdownGracefully();


            workers.shutdownGracefully();


        }


    }


    /** Stop ticking + drop arena binding. */


    static void stopTicker() {


        ScheduledFuture<?> f = SIM_LOOP;


        SIM_LOOP = null;


        ARENA_REF.set(null);


        if (f != null) {


            f.cancel(false);

        }


    }


    /** No TCP clients left — drop arena/ticker so the next session can Ready-queue normally. */


    static void shutdownArenaIfNobodyOnline() {


        if (!SESSIONS.isEmpty()) {


            return;


        }


        if (ARENA_REF.get() != null || SIM_LOOP != null) {


            stopTicker();


        }


        COUNTDOWN_RUNNING = false;


    }


    static void broadcastRoster() {


        ByteBuf frame = LobbyTcpOutbound.rosterBroadcast(UDP.alloc(), rosterSnapshotLines());


        ONLINE.writeAndFlush(frame);

    }


    static List<PlayerInfo> rosterSnapshotLines() {


        return SESSIONS.values().stream()
                .filter(LobbyPeer::handshakeFinished)


                .sorted(Comparator.comparingInt(pk -> pk.info.id()))


                .map(pk -> new PlayerInfo(pk.info.id(), pk.info.nickname(), pk.readyToggle))


                .toList();

    }


    static String normaliseNickname(String nickname) {


        return nickname.trim().toLowerCase(Locale.ROOT);


    }


    static void lobbyCompositionChanged() {


        LOBBY_EPOCH++;


        COUNTDOWN_RUNNING = false;


        for (LobbyPeer peer : SESSIONS.values()) {


            peer.readyToggle = false;


        }


    }


    /** Called when READY toggles — may enqueue match staging on UDP loop. */


    static void tryBeginMatchScheduling() {
        if (UDP == null || COUNTDOWN_RUNNING) {
            return;
        }


        List<LobbyPeer> everyone = SESSIONS.values().stream()
                .filter(LobbyPeer::handshakeFinished)
                .sorted(Comparator.comparingInt(p -> p.info.id()))
                .toList();


        if (everyone.size() < MIN_PLAYERS) {
            return;
        }


        if (!everyone.stream().allMatch(LobbyPeer::isReadyToggle)) {
            return;
        }

        if (ARENA_REF.get() != null) {
            stopTicker();
        }

        final int epochSnap = LOBBY_EPOCH;


        COUNTDOWN_RUNNING = true;


        final List<LobbyPeer> immutable = List.copyOf(everyone);


        UDP.eventLoop().execute(() -> stageCountdown(immutable, epochSnap));

    }


    private static void stageCountdown(List<LobbyPeer> roster, int epochSnapshot) {


        if (epochSnapshot != LOBBY_EPOCH) {


            COUNTDOWN_RUNNING = false;


            return;


        }


        int wireMid = MATCH_IDS.incrementAndGet();


        int cds = MATCH_COUNTDOWN;


        int udpBoundPort =
                UDP.localAddress().getPort();


        int howManyPlayers = roster.size();


        ArrayList<MatchParticipant> maths = new ArrayList<>(howManyPlayers);


        for (int idx =


                0; idx <
                        howManyPlayers; idx++) {


            LobbyPeer member = roster.get(idx);


            int slot = idx +
                    1;


            float angle =
                    howManyPlayers == 1 ?
                            0f :


                            (float)


                                    (
                                            2.0 *

                                                    Math.PI * idx /
                                                    howManyPlayers);


            float spawnX =
                    (float)

                            Math.cos(angle) *

                            52f;


            float spawnY =
                    (float)

                            Math.sin(angle) *

                            52f;


            member.spawnPx = spawnX;


            member.spawnPy =
                    spawnY;


            member.slot = slot;


            InetAddress ipa = unwrapRemoteIp(member.socket());

            maths.add(new MatchParticipant(slot, spawnX, spawnY, ipa));

            ByteBuf mf =
                    LobbyTcpOutbound.matchFound(
                            UDP.alloc(),
                            wireMid & 0xffffffffL,
                            UDP_ADVERT_HOST,
                            udpBoundPort,
                            slot,
                            howManyPlayers,
                            spawnX,
                            spawnY,
                            cds);

            member.socket().writeAndFlush(mf);


        }


        scheduleCountdownPulses(roster, cds, epochSnapshot);

        final List<LobbyPeer> frozenLobby = List.copyOf(roster);

        final List<MatchParticipant> frozenMaths = List.copyOf(maths);

        UDP.eventLoop()
                .schedule(
                        () -> {

                            if (epochSnapshot != LOBBY_EPOCH) {


                                COUNTDOWN_RUNNING = false;


                                return;


                            }


                            commenceArena(frozenLobby, wireMid, frozenMaths);

                        },
                        (long) cds * 1000L,
                        TimeUnit.MILLISECONDS);

    }


    private static void scheduleCountdownPulses(List<LobbyPeer> roster,
                                                int cds,
                                                int epochSnapshot) {


        for (int step =

                1; step <=
                        cds; step++) {


            final int secondsLeftEcho = cds - step +
                    1;


            final int stepFinal = step;


            UDP.eventLoop()
                    .schedule(
                            () -> {

                                if (epochSnapshot != LOBBY_EPOCH) {


                                    return;


                                }


                                fanoutSeconds(roster, secondsLeftEcho);

                            },

                            (long)

                                    (stepFinal - 1),

                            TimeUnit.SECONDS);

        }


    }


    private static void fanoutSeconds(List<LobbyPeer> roster,
                                      int secs) {


        for (LobbyPeer p : roster) {


            ByteBuf buf = LobbyTcpOutbound.matchCountdown(p.socket().alloc(), secs);

            p.socket().writeAndFlush(buf);

        }

    }


    private static void commenceArena(
            List<LobbyPeer> participantChannels, int wireMidBits, List<MatchParticipant> mathsRoster) {

        COUNTDOWN_RUNNING = false;

        stopTicker();

        MatchRuntime rt = new MatchRuntime(wireMidBits, mathsRoster);

        ARENA_REF.set(rt);

        int wallMs = clampToUnsignedIntExact(System.currentTimeMillis());

        ByteBuf blast = LobbyTcpOutbound.matchStart(UDP.alloc(), 0, wallMs);

        for (LobbyPeer peer : participantChannels) {
            peer.readyToggle = false;
            peer.socket().writeAndFlush(blast.retainedDuplicate());
        }
        broadcastRoster();
        blast.release();

        SIM_LOOP =
                UDP.eventLoop()
                        .scheduleAtFixedRate(
                                () -> {
                                    MatchRuntime head = ARENA_REF.get();
                                    if (head != null) {
                                        head.advanceAndMulticast(UDP);
                                    }
                                },
                                50,
                                50,
                                TimeUnit.MILLISECONDS);
    }

    private static InetAddress unwrapRemoteIp(io.netty.channel.Channel upstream) {


        return ((InetSocketAddress) upstream.remoteAddress()).getAddress();

    }


    private static int clampToUnsignedIntExact(long wall) {


        return (int) (wall &
                0xffffffffL);


    }


    static final class LobbyPeer {


        private final io.netty.channel.Channel channelBridge;

        private final PlayerInfo info;

        volatile boolean handshakeFinished;


        volatile boolean readyToggle;


        float spawnPx;

        float spawnPy;

        int slot;


        LobbyPeer(io.netty.channel.Channel channelBridge,

                  PlayerInfo info) {


            this.channelBridge =
                    channelBridge;


            this.info =
                    info;

        }


        io.netty.channel.Channel socket() {


            return channelBridge;

        }


        boolean handshakeFinished() {


            return handshakeFinished;


        }


        boolean isReadyToggle() {


            return readyToggle;

        }


    }


    /** Per-TCP-connection lobby shim. */


    static final class LobbyHandler extends SimpleChannelInboundHandler<Object> {


        LobbyPeer selfAttach;


        @Override


        protected void channelRead0(ChannelHandlerContext ctx,
                                    Object inbound) {


            if (inbound instanceof Handshake handshake) {


                ingestHandshake(ctx,
                        handshake);

            } else if (inbound == ReadyC2S.INSTANCE) {


                if (selfAttach !=
                        null) {


                    selfAttach.readyToggle =


                            true;


                    broadcastRoster();


                    tryBeginMatchScheduling();

                }

            }


            else if (inbound == LeaveLobbyC2S.INSTANCE) {


                ctx.close();

            }


        }


        private void ingestHandshake(ChannelHandlerContext ctx,

                                     Handshake hs) {


            if (selfAttach !=


                    null) {


                ctx.close();

                return;


            }


            final String trimmed = hs.nickname().trim();


            if (trimmed.isBlank() || trimmed.length() >
                    32) {


                ctx.close();


                return;

            }


            String finger = NetworkServer.normaliseNickname(trimmed);

            Boolean collision =


                    NetworkServer.NICK_BOOK.putIfAbsent(finger, Boolean.TRUE);


            if (collision !=


                    null) {


                ctx.close();

                return;

            }


            int lobbySerial = GLOBAL_LOBBY_IDS.getAndIncrement();

            LobbyPeer neo =


                    new LobbyPeer(ctx.channel(), new PlayerInfo(lobbySerial, trimmed, false));


            SESSIONS.put(ctx.channel().id(), neo);


            ONLINE.add(ctx.channel());

            selfAttach =
                    neo;

            neo.handshakeFinished =
                    true;


            ctx.writeAndFlush(LobbyTcpOutbound.welcome(ctx.alloc(), neo.info.id()));

            lobbyCompositionChanged();


            broadcastRoster();


        }


        @Override


        public void channelInactive(ChannelHandlerContext ctx) {


            LobbyPeer left = SESSIONS.remove(ctx.channel().id());

            if (left !=
                    null) {


                ONLINE.remove(ctx.channel());

                NICK_BOOK.remove(normaliseNickname(left.info.nickname()));


                lobbyCompositionChanged();


                broadcastRoster();

                MatchRuntime rt = ARENA_REF.get();
                if (rt != null && left.slot > 0 && UDP != null) {
                    UDP.eventLoop().execute(() -> rt.disconnectPlayer(left.slot));
                }

                shutdownArenaIfNobodyOnline();


            }


        }


        @Override


        public void exceptionCaught(ChannelHandlerContext ctx,

                                    Throwable thr) {


            thr.printStackTrace();


            ctx.close();

        }


    }


    private NetworkServer() {


    }


}
