package org.nethergames.proxytransport.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import lombok.AllArgsConstructor;
import org.nethergames.proxytransport.ProxyTransport;
import org.nethergames.proxytransport.impl.TransportDownstreamSession;
import org.nethergames.proxytransport.protocol.packet.ExtensionPacket;

import java.util.List;

@AllArgsConstructor
public class ExtensionPacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final TransportDownstreamSession session;

    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf byteBuf, List<Object> list) throws Exception {
        int packetId = byteBuf.readInt();

        if (packetId == 0) {
            // this is a bedrock packet
            list.add(byteBuf.retainedSlice());
            return;
        }

        ExtensionPacket packet = ProxyTransport.getInstance().getExtensionPacketPool().forgeFrom(packetId);

        if (packet != null) {
            packet.decode(byteBuf);
            ProxyTransport.getEventAdapter().extensionPacketReceived(packet, this.session.getPlayer());
        } else {
            this.session.getPlayer().getLogger().warning("Received invalid extension packet from downstream of " + this.session.getPlayer().getName() + ". Unknown Packet ID " + packetId);
        }
    }
}
