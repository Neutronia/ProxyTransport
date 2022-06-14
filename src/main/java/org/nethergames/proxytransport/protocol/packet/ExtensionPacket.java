package org.nethergames.proxytransport.protocol.packet;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

public abstract class ExtensionPacket {
    public abstract void decode(ByteBuf buf);

    public abstract void encode(ByteBuf buf);

    public abstract int getPacketId();

    public void writeString(ByteBuf buf, String val) {
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public String readString(ByteBuf buf){
        final int length = buf.readInt();
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
