package org.nethergames.proxytransport.protocol;

import com.nukkitx.network.VarInts;
import com.nukkitx.protocol.bedrock.BedrockPacket;
import io.netty.buffer.ByteBuf;

import java.util.Collection;

public class BufferValidator {
    public static boolean ensureNoInternal(ByteBuf targetBuffer, PacketPool pool) {
        targetBuffer.markReaderIndex();
        boolean found = false;
        while (targetBuffer.isReadable()) {
            int length = VarInts.readUnsignedInt(targetBuffer); // length
            int currentReaderIndex = targetBuffer.readerIndex();
            int packetId = VarInts.readUnsignedInt(targetBuffer) & 1023; // packet id
            int nextReaderIndex = targetBuffer.readerIndex();
            if (pool.isRegistered(packetId)) {
                found = true;
                break;
            }

            targetBuffer.skipBytes(length - (nextReaderIndex - currentReaderIndex)); // skip all the remaining bytes of this packet
        }

        return found;
    }

    public static boolean ensureNoInternal(Collection<BedrockPacket> packets, PacketPool pool) {
        for (BedrockPacket packet : packets) {
            if (pool.isRegistered(packet.getPacketId())) {
                return true;
            }
        }

        return false;
    }

    public static boolean ensureNoInternal(BedrockPacket packet, PacketPool pool) {
        return pool.isRegistered(packet.getPacketId());
    }
}
