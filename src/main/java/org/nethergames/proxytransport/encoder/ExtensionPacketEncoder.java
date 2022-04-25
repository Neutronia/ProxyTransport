package org.nethergames.proxytransport.encoder;

import com.nukkitx.network.VarInts;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.nethergames.proxytransport.protocol.packet.ExtensionPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtensionPacketEncoder extends MessageToByteEncoder<ExtensionPacket> {
    private static final Logger logger = LoggerFactory.getLogger("ExtensionPacketEncoder");
    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, ExtensionPacket extensionPacket, ByteBuf byteBuf) throws Exception {
        ByteBuf packetBuffer = byteBuf.alloc().buffer();

        try{
            int header = 0;
            header = header | extensionPacket.getPacketId() & 1023;
            header |= (0) << 10;
            header |= (0) << 12;

            VarInts.writeUnsignedInt(packetBuffer, header);

            extensionPacket.encode(packetBuffer);
            VarInts.writeUnsignedInt(byteBuf, packetBuffer.readableBytes());
            byteBuf.writeBytes(packetBuffer);
        }catch(Throwable t){
            logger.error("Error while encoding packet of type " + extensionPacket.getClass().getName(), t);
        }finally{
            packetBuffer.release();
        }

    }
}
