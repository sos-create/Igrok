package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

final class IgrokServerPlayer extends ServerPlayer {
    private final FakeServerGamePacketListener fakeListener;

    IgrokServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile, Connection connection) {
        super(server, level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        this.fakeListener = new FakeServerGamePacketListener(
                server, connection, this, CommonListenerCookie.createInitial(profile, false));
        this.connection = fakeListener;
    }

    void restoreFakeListener() {
        this.connection = fakeListener;
    }

    @Override
    public void tick() {
        if (isRemoved()) return;
        // ServerPlayer.tick() already invokes doTick(). Calling it again here
        // advanced player state twice per server tick and could desynchronize
        // movement, hunger, inventory and world interactions.
        super.tick();
    }
}
