package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

final class IgrokServerPlayer extends ServerPlayer {
    private final FakeServerGamePacketListener fakeListener;

    IgrokServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
                      Connection connection, net.minecraft.server.level.ClientInformation clientInformation) {
        super(server, level, profile, clientInformation);
        CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, clientInformation, false);
        this.fakeListener = new FakeServerGamePacketListener(server, connection, this, cookie);
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
