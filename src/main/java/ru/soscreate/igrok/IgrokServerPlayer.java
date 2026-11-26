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
    private double fallStartY = Double.NaN;

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
        // movement, item pickup and hunger without the network listener snap-back.
        boolean groundedBefore = getBukkitEntity().isOnGround();
        double yBefore = getY();
        doTick();
        if (isRemoved() || !isAlive()) return;

        boolean groundedAfter = getBukkitEntity().isOnGround();
        if (groundedBefore && !groundedAfter) {
            fallStartY = yBefore;
        } else if (!groundedBefore && !groundedAfter && Double.isNaN(fallStartY)) {
            fallStartY = yBefore;
        } else if (!groundedBefore && groundedAfter) {
            if (Double.isFinite(fallStartY)) {
                float distance = (float) Math.max(0.0, fallStartY - getY());
                if (distance > 3.0F && !isInWater()) {
                    float multiplier = 1.0F;
                    net.minecraft.core.BlockPos landingPos = blockPosition().below();
                    net.minecraft.world.level.block.state.BlockState landing =
                            level().getBlockState(landingPos);
                    if (landing.is(net.minecraft.world.level.block.Blocks.HAY_BLOCK)) {
                        multiplier = 0.2F;
                    } else if (landing.is(net.minecraft.world.level.block.Blocks.BED)) {
                        multiplier = 0.5F;
                    }
                    if (multiplier > 0.0F) {
                        causeFallDamage(distance, multiplier, damageSources().fall());
                    }
                }
            }
            fallStartY = Double.NaN;
        }
    }

}
