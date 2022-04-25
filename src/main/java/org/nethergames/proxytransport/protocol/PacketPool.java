package org.nethergames.proxytransport.protocol;

import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.nethergames.proxytransport.protocol.packet.ExtensionPacket;

import java.util.function.Supplier;

public class PacketPool {
    private static final Int2ObjectMap<Supplier<? extends ExtensionPacket>> extensionPacketMap = new Int2ObjectArrayMap<>();

    public void register(int packetId, Supplier<? extends ExtensionPacket> factoryMethod) {
        extensionPacketMap.put(packetId, factoryMethod);
    }

    public boolean isRegistered(int id){
        return extensionPacketMap.containsKey(id);
    }
}
