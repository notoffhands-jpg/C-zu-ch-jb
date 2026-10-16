package de.fiden.botsystem.bot;

import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import javax.crypto.SecretKey;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

public final class FakeConnections {
    private static final Set<Connection> FAKE = Collections.newSetFromMap(new WeakHashMap<>());

    private FakeConnections() {
    }

    public static FakeConnections.FakeConnection create() {
        FakeConnections.FakeConnection connection = new FakeConnections.FakeConnection();
        synchronized (FAKE) {
            FAKE.add(connection);
            return connection;
        }
    }

    public static boolean isFake(Connection connection) {
        if (connection instanceof FakeConnections.FakeConnection) {
            return true;
        }

        synchronized (FAKE) {
            return FAKE.contains(connection);
        }
    }

    static final class EmptyChannel extends AbstractChannel {
        /**
         * One idle loop shared by every bot. Without any, plugins that hook each player's channel
         * on join (ProtocolLib) crashed with "channel not registered to an event loop" - a full
         * stack trace in the log for every single bot spawned. With it they see an inactive
         * channel and leave the bot alone.
         */
        private static final EventLoop LOOP = new io.netty.channel.DefaultEventLoop(runnable -> {
            Thread thread = new Thread(runnable, "BotSystem-FakeChannel");
            thread.setDaemon(true);
            return thread;
        });
        private final ChannelConfig config = new DefaultChannelConfig(this);

        @Override
        public EventLoop eventLoop() {
            return LOOP;
        }

        EmptyChannel() {
            super(null);
        }

        @Override
        public ChannelConfig config() {
            this.config.setAutoRead(true);
            return this.config;
        }

        @Override
        protected void doBeginRead() {
        }

        @Override
        protected void doBind(SocketAddress localAddress) {
        }

        @Override
        protected void doClose() {
        }

        @Override
        protected void doDisconnect() {
        }

        @Override
        protected void doWrite(ChannelOutboundBuffer in) {
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        protected boolean isCompatible(EventLoop loop) {
            return true;
        }

        @Override
        protected SocketAddress localAddress0() {
            return null;
        }

        @Override
        public ChannelMetadata metadata() {
            return new ChannelMetadata(true);
        }

        @Override
        protected AbstractUnsafe newUnsafe() {
            return null;
        }

        @Override
        protected SocketAddress remoteAddress0() {
            return null;
        }
    }

    public static final class FakeConnection extends Connection {
        private PacketListener listener;
        private boolean closed;

        FakeConnection() {
            super(PacketFlow.SERVERBOUND);
            this.channel = new FakeConnections.EmptyChannel();
            this.address = new InetSocketAddress("127.0.0.1", 0);
        }

        @Override
        public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetInfo) {
            this.listener = packetInfo;
        }

        @Override
        public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
        }

        @Override
        public void setListenerForServerboundHandshake(PacketListener packetListener) {
            this.listener = packetListener;
        }

        @Override
        public PacketListener getPacketListener() {
            return this.listener;
        }

        @Override
        public void send(Packet<?> packet) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        }

        @Override
        public void runOnceConnected(Consumer<Connection> action) {
        }

        @Override
        public void flushChannel() {
        }

        @Override
        public void tick() {
        }

        @Override
        public SocketAddress getRemoteAddress() {
            return this.address;
        }

        @Override
        public String getLoggableAddress(boolean logIps) {
            return "bot";
        }

        @Override
        public void disconnect(Component reason) {
            this.closed = true;
        }

        @Override
        public void disconnect(DisconnectionDetails details) {
            this.closed = true;
        }

        @Override
        public boolean isMemoryConnection() {
            return true;
        }

        @Override
        public boolean isConnected() {
            return !this.closed;
        }

        @Override
        public boolean isConnecting() {
            return false;
        }

        @Override
        public void setReadOnly() {
        }

        @Override
        public void enableAutoRead() {
        }

        @Override
        public void setupCompression(int threshold, boolean validate) {
        }

        @Override
        public void setEncryptionKey(SecretKey key) {
        }
    }
}
