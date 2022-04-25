package org.nethergames.proxytransport.protocol.packet;

import io.netty.buffer.ByteBuf;

public abstract class ExtensionPacket {
    public abstract void decode(ByteBuf buf);
    public abstract void encode(ByteBuf buf);
    public abstract int getPacketId();
}
