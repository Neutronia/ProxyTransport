package org.nethergames.proxytransport.protocol.packet;

import io.netty.buffer.ByteBuf;

public class LoginDetailTransmissionPacket extends ExtensionPacket {
    public static final int NETWORK_ID = 408;
    @Override
    public void decode(ByteBuf buf) {

    }

    @Override
    public void encode(ByteBuf buf) {

    }

    @Override
    public int getPacketId() {
        return NETWORK_ID;
    }
}
