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

        // Phase one: ServerPlayer bookkeeping (game mode, menus, attributes, etc.).
        super.tick();
        if (isRemoved() || !isAlive()) return;

        // Phase two: actual vanilla player movement and physics. A real player gets
        // doTick() from ServerGamePacketListenerImpl.tickPlayer(). Igrok has no
        // registered client socket, so that call never happens for this fake player.
        // Calling doTick() here once per world tick gives it normal gravity, collisions,
        // movement, fall damage, item pickup and hunger without network snap-back.
        // Fall damage is already processed by vanilla Entity.checkFallDamage(); do not
        // call causeFallDamage() again here or hard landings would deal damage twice.
        // Preserve independent body/head yaw while vanilla physics applies movement.
        float intendedYaw = getYRot();
        float intendedPitch = getXRot();
        float intendedHeadYaw = getYHeadRot();
        float intendedBodyYaw = yBodyRot;
        doTick();
        if (isRemoved() || !isAlive()) return;

        setYRot(intendedYaw);
        setXRot(intendedPitch);
        setYHeadRot(intendedHeadYaw);
        setYBodyRot(intendedBodyYaw);
    }

}
