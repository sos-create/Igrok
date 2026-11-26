package ru.soscreate.igrok;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Fake packet listener: no socket exists, so outgoing packets must not accumulate. */
final class FakeServerGamePacketListener extends ServerGamePacketListenerImpl {
    FakeServerGamePacketListener(MinecraftServer server, Connection connection, ServerPlayer player,
                                 CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override public void tick() { }

    @Override
    public void send(Packet<?> packet) { }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) { }
}
