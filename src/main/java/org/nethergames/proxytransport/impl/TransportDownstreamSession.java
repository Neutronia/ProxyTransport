package org.nethergames.proxytransport.impl;

import com.nukkitx.network.util.DisconnectReason;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import com.nukkitx.protocol.bedrock.handler.BedrockPacketHandler;
import com.nukkitx.protocol.bedrock.packet.NetworkStackLatencyPacket;
import com.nukkitx.protocol.bedrock.packet.TickSyncPacket;
import com.nukkitx.protocol.bedrock.packet.UnknownPacket;
import dev.waterdog.waterdogpe.network.bridge.AbstractDownstreamBatchBridge;
import dev.waterdog.waterdogpe.network.bridge.TransferBatchBridge;
import dev.waterdog.waterdogpe.network.downstream.ConnectedDownstreamHandler;
import dev.waterdog.waterdogpe.network.downstream.InitialHandler;
import dev.waterdog.waterdogpe.network.downstream.SwitchDownstreamHandler;
import dev.waterdog.waterdogpe.network.session.DownstreamClient;
import dev.waterdog.waterdogpe.network.session.bedrock.BedrockDownstreamBridge;
import dev.waterdog.waterdogpe.player.ProxiedPlayer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.ReferenceCounted;
import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SentryEvent;
import io.sentry.SpanStatus;
import org.nethergames.proxytransport.decoder.PacketDecoder;
import org.nethergames.proxytransport.encoder.DataPackEncoder;
import org.nethergames.proxytransport.encoder.ZStdEncoder;
import org.nethergames.proxytransport.integration.CustomTransportBatchBridge;
import org.nethergames.proxytransport.protocol.packet.ExtensionPacket;
import org.nethergames.proxytransport.utils.BedrockBatch;
import org.nethergames.proxytransport.wrapper.DataPack;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.nukkitx.network.util.DisconnectReason.BAD_PACKET;

public class TransportDownstreamSession implements dev.waterdog.waterdogpe.network.session.DownstreamSession {

    private static final int PING_CYCLE_TIME = 2; // 2 seconds
    private static final long MAX_UPSTREAM_PACKETS = 750;
    private static final ScheduledExecutorService focusedResetTimer = Executors.newScheduledThreadPool(4);

    private final AtomicInteger packetSendingLimit = new AtomicInteger(0);
    private final AtomicBoolean disconnected = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicBoolean networkStackLatencyLock = new AtomicBoolean(false); // true if we already sent one and wait for the response
    private final DownstreamClient client;
    private final Channel channel;
    private final ChannelPromise voidPromise;
    private final List<Consumer<DisconnectReason>> disconnectHandlers = new ArrayList<>();
    private final ITransaction sentryTransaction;
    private BedrockPacketHandler packetHandler;
    private AbstractDownstreamBatchBridge batchHandler;
    private ProxiedPlayer player;
    private long lastPingTimestamp;
    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> limitResetFuture;
    private long latency = -1;
    private ISpan currentSpan;

    public TransportDownstreamSession(Channel channel, DownstreamClient client, ITransaction transaction) {
        this.channel = channel;
        this.voidPromise = channel.voidPromise();
        this.client = client;
        this.sentryTransaction = transaction;
        this.configurePipeline(this.channel);
    }

    @Override
    public void onDownstreamInit(ProxiedPlayer proxiedPlayer, boolean initial) {
        sentryTransaction.setTag("user", "id:" + proxiedPlayer.getXuid());
        sentryTransaction.setData("loginData", proxiedPlayer.getLoginData());

        ISpan span = this.sentryTransaction.startChild("downstream-init");
        span.setData("isInitial", initial);

        this.player = proxiedPlayer;

        this.pingFuture = this.getChannel().eventLoop().scheduleAtFixedRate(this::determinePing, PING_CYCLE_TIME, PING_CYCLE_TIME, TimeUnit.SECONDS);
        this.limitResetFuture = focusedResetTimer.scheduleAtFixedRate(() -> this.packetSendingLimit.set(0), 1, 1, TimeUnit.SECONDS);

        if (initial) {
            currentSpan = sentryTransaction.startChild("start-game");
            this.setPacketHandler(new InitialHandler(proxiedPlayer, this.client));
            this.setBatchHandler(new BedrockDownstreamBridge(player, player.getUpstream()));
        } else {
            currentSpan = sentryTransaction.startChild("connect-switch");
            this.setPacketHandler(new SwitchDownstreamHandler(player, this.client));
            this.setBatchHandler(new CustomTransportBatchBridge(player, this, player.getUpstream()));
            this.addDisconnectHandler(reason -> TransferBatchBridge.release(this.getBatchHandler()));
        }

        span.finish(SpanStatus.OK);
    }

    @Override
    public void onInitialServerConnected(ProxiedPlayer proxiedPlayer) {
        this.setPacketHandler(new ConnectedDownstreamHandler(player, this.client));
        currentSpan.finish(SpanStatus.OK);
        this.sentryTransaction.finish(SpanStatus.OK);
    }

    @Override
    public void onServerConnected(ProxiedPlayer proxiedPlayer) {
        TransferBatchBridge batchBridge = this.getBatchBridge();
        if (batchBridge != null) {
            batchBridge.setDimLockActive(true);
        }
    }

    @Override
    public void onTransferCompleted(ProxiedPlayer proxiedPlayer, Runnable runnable) {
        TransferBatchBridge batchBridge = this.getBatchBridge();
        if (batchBridge != null) {
            batchBridge.setDimLockActive(false);
        }

        EventLoop loop = this.getChannel().eventLoop();
        if (loop.inEventLoop()) {
            this.onTransferCompleted0(player, runnable);
        } else {
            loop.execute(() -> this.onTransferCompleted0(player, runnable));
        }
    }

    public void onTransferCompleted0(ProxiedPlayer player, Runnable callback) {
        TransferBatchBridge bridge = this.getBatchBridge();
        this.setBatchHandler(new BedrockDownstreamBridge(player, player.getUpstream()));
        this.setPacketHandler(new ConnectedDownstreamHandler(player, this.client));

        if (bridge != null) {
            bridge.flushQueue();
        }
        callback.run();

        currentSpan.finish(SpanStatus.OK);
        this.sentryTransaction.finish(SpanStatus.OK);
    }

    @Override
    public void addDisconnectHandler(Consumer<Object> consumer) {
        Objects.requireNonNull(consumer, "disconnectHandler cannot be null");
        this.disconnectHandlers.add(consumer::accept);
    }

    @Override
    public void sendPacket(BedrockPacket bedrockPacket) {
        sendPacketImmediately(bedrockPacket);
    }

    @Override
    public void sendPacketImmediately(BedrockPacket bedrockPacket) {
        ByteBuf encoded = BedrockBatch.encodeSingle(bedrockPacket, this);
        try {
            ByteBuf compressed = ZStdEncoder.compress(encoded);
            DataPack pack = new DataPack(DataPack.CompressionType.METHOD_ZSTD, compressed);
            this.channel.writeAndFlush(pack, this.voidPromise);
        } finally {
            encoded.release();
        }

    }

    private void releasePackets(Collection<BedrockPacket> collection) {
        for (BedrockPacket packet : collection) {
            if (packet instanceof ReferenceCounted) {
                ((ReferenceCounted) packet).release();
            }
        }
    }

    @Override
    public void sendWrapped(Collection<BedrockPacket> collection, boolean b) {
        this.packetSendingLimit.set(this.packetSendingLimit.get() + collection.size());
        if (this.packetSendingLimit.get() >= MAX_UPSTREAM_PACKETS) {
            this.getPlayer().getLogger().warning(this.getPlayer().getName() + " sent too many packets (" + this.packetSendingLimit.get() + "/s), disconnecting. Session status: " + this.channel.isActive() + ":" + this.disconnected.get() + ":" + this.limitResetFuture.isCancelled() + ":" + (this.getPlayer().getServerInfo() != null ? this.getPlayer().getServerInfo().getServerName() : "None") + ":" + (this.getPlayer().getPendingConnection() != null ? this.getPlayer().getPendingConnection().getServerInfo().getServerName() : "None"));
            this.getPlayer().getUpstream().disconnect("§cToo many packets!");
            releasePackets(collection);
            return;
        }

        if (this.disconnected.get() || this.player == null || !this.channel.isActive() || !this.channel.isWritable()) {
            releasePackets(collection);
            return;
        }

        ByteBuf buf = BedrockBatch.encodePackets(collection, this);
        try {
            DataPack pack = new DataPack(DataPack.CompressionType.METHOD_ZSTD, ZStdEncoder.compress(buf));
            this.channel.writeAndFlush(pack, this.voidPromise);
        } finally {
            buf.release();
        }
    }

    public void attachCurrentTransaction(SentryEvent event) {
        if (this.currentSpan != null && !this.currentSpan.isFinished()) {
            event.setTransaction(this.currentSpan.getSpanContext().getSpanId().toString());
        }
    }

    @Override
    public int getHardcodedBlockingId() {
        return this.player.getUpstream().getHardcodedBlockingId().get();
    }

    @Override
    public InetSocketAddress getAddress() {
        return (InetSocketAddress) getChannel().localAddress();
    }

    @Override
    public long getLatency() {
        return this.latency;
    }

    @Override
    public boolean isClosed() {
        return this.disconnected.get();
    }

    @Override
    public void disconnect() {
        this.disconnect0(DisconnectReason.DISCONNECTED);
    }

    public void disconnect(DisconnectReason reason) {
        this.disconnect0(reason);

        if (reason == BAD_PACKET) {
            // Send player to any fallback server after receiving a bad packet as a disconnect reason.
            player.sendToFallback(this.client.getServerInfo(), "Downstream Timeout (Bad Packet)");
        }
    }

    @Override
    public void sendWrapped(ByteBuf byteBuf, boolean b) {
        if (!this.ready.get() || !this.channel.isActive()) {
            return;
        }

        DataPack pack = new DataPack(DataPack.CompressionType.METHOD_ZLIB, byteBuf.retain());

        this.channel.writeAndFlush(pack);
    }

    private void disconnect0(DisconnectReason reason) {
        if (this.disconnected.get()) return;
        this.disconnected.set(true);

        for (Consumer<DisconnectReason> disconnectHandler : this.disconnectHandlers) {
            disconnectHandler.accept(reason);
        }

        if (this.limitResetFuture != null) {
            this.limitResetFuture.cancel(false);
        }

        if (this.pingFuture != null) {
            this.pingFuture.cancel(false);
        }

        this.channel.close();
    }

    private TransferBatchBridge getBatchBridge() {
        if (this.getBatchHandler() instanceof TransferBatchBridge) {
            return (TransferBatchBridge) this.getBatchHandler();
        }
        return null;
    }

    public void configurePipeline(Channel socketChannel) {
        ChannelPipeline pipeline = socketChannel.pipeline();
        pipeline.addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4));
        pipeline.addLast(new LengthFieldPrepender(4));
        pipeline.addLast(new DataPackEncoder());
        pipeline.addLast(new PacketDecoder(this));

        this.ready.set(true);
    }

    public void sendExtensionPacket(ExtensionPacket packet){
        ByteBuf buf = ByteBufAllocator.DEFAULT.buffer();
        try{
            packet.encode(buf);
            UnknownPacket packet1 = new UnknownPacket();
            packet1.setPacketId(packet.getPacketId());
            packet1.setPayload(buf.retain());

            this.sendPacketImmediately(packet1);
        }catch(Throwable t){
            this.player.getLogger().error("Error while sending extension packet", t);
        }finally{
            buf.release();
        }

    }

    public void handleNetworkStackPacket() {
        this.latency = (System.currentTimeMillis() - this.lastPingTimestamp) / 2;
        this.networkStackLatencyLock.set(false);

        // TODO: Filter upstream packet from sending TickSyncPacket to downstream server.
        //       This could possibly cause the AntiCheat to false-positives player latency if
        //       the client sent their own fake latency (Which is a big vulnerability if you ask me).
        TickSyncPacket latencyPacket = new TickSyncPacket();
        latencyPacket.setRequestTimestamp(player.getPing());
        if (player.getDownstream() != null && player.getDownstream().getSession() != null) {
            latencyPacket.setResponseTimestamp(player.getDownstream().getSession().getLatency());
        } else {
            latencyPacket.setResponseTimestamp(0);
        }

        this.sendPacket(latencyPacket);
    }

    private void determinePing() {
        if (!this.channel.isOpen() || this.networkStackLatencyLock.get()) return;

        NetworkStackLatencyPacket packet = new NetworkStackLatencyPacket();
        packet.setTimestamp(0);
        packet.setFromServer(true);
        this.sendPacket(packet);
        this.lastPingTimestamp = System.currentTimeMillis();
    }

    public ProxiedPlayer getPlayer() {
        return player;
    }

    public Channel getChannel() {
        return channel;
    }

    public AbstractDownstreamBatchBridge getBatchHandler() {
        return batchHandler;
    }

    public void setBatchHandler(AbstractDownstreamBatchBridge batchHandler) {
        this.batchHandler = batchHandler;
    }

    public BedrockPacketHandler getPacketHandler() {
        return packetHandler;
    }

    public void setPacketHandler(BedrockPacketHandler packetHandler) {
        this.packetHandler = packetHandler;
    }

}
