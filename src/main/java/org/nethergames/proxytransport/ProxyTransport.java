package org.nethergames.proxytransport;

import dev.waterdog.waterdogpe.plugin.Plugin;
import lombok.Getter;
import org.nethergames.proxytransport.integration.CustomTransportServerInfo;
import org.nethergames.proxytransport.protocol.PacketPool;
import org.nethergames.proxytransport.protocol.packet.LoginDetailTransmissionPacket;


@Getter
public class ProxyTransport extends Plugin {
    private static ProxyTransport instance;
    private final PacketPool extensionPacketPool = new PacketPool();
    @Override
    public void onEnable() {
        instance = this;
        registerExtensionPackets();
        getProxy().getServerInfoMap().removeServerInfoType(CustomTransportServerInfo.TYPE);
        getProxy().getServerInfoMap().registerServerInfoFactory(CustomTransportServerInfo.TYPE, CustomTransportServerInfo::new);
    }

    public void registerExtensionPackets(){
        this.extensionPacketPool.register(LoginDetailTransmissionPacket.NETWORK_ID, LoginDetailTransmissionPacket::new);
    }

    public static ProxyTransport getInstance() {
        return instance;
    }
}
