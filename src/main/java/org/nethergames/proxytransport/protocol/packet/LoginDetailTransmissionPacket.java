package org.nethergames.proxytransport.protocol.packet;

import io.netty.buffer.ByteBuf;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class LoginDetailTransmissionPacket extends ExtensionPacket {
    public static final int NETWORK_ID = 408;

    private String originIp;
    private String xuid;

    @Override
    public void decode(ByteBuf buf) {
        this.originIp = readString(buf);
        this.xuid = readString(buf);
    }

    @Override
    public void encode(ByteBuf buf) {
        writeString(buf, this.originIp);
        writeString(buf, this.xuid);
    }

    @Override
    public int getPacketId() {
        return NETWORK_ID;
    }
}
