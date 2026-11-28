package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;

final class IgrokServerPlayer extends ServerPlayer {
    private final FakeServerGamePacketListener fakeListener;

    // Navigation writes an intent at the AI decision rate (normally every two
    // ticks); velocity is then smoothed here on every world tick.
    private boolean movementControllerActive;
    private boolean movementIntentSet;
    private double movementTargetX;
    private double movementTargetZ;
    private double controlledMotionX;
    private double controlledMotionZ;

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

    void setMovementIntent(double directionX, double directionZ, double speed) {
        if (!Double.isFinite(directionX) || !Double.isFinite(directionZ)
                || !Double.isFinite(speed) || speed <= 0.0) {
            clearMovementIntent();
            return;
        }
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (length < 0.0001) {
            clearMovementIntent();
            return;
        }

        if (!movementControllerActive) {
            Vec3 current = getDeltaMovement();
            controlledMotionX = current.x;
            controlledMotionZ = current.z;
        }
        double safeSpeed = Math.min(0.32, speed);
        movementTargetX = directionX / length * safeSpeed;
        movementTargetZ = directionZ / length * safeSpeed;
        movementIntentSet = true;
        movementControllerActive = true;
    }

    void clearMovementIntent() {
        movementIntentSet = false;
        movementTargetX = 0.0;
        movementTargetZ = 0.0;
        // Keep the controller alive until its stored velocity has eased to zero.
    }

    /** Preserve a vanilla hit impulse instead of overwriting it with stale navigation. */
    void releaseMovementForExternalImpulse() {
        Vec3 current = getDeltaMovement();
        controlledMotionX = current.x;
        controlledMotionZ = current.z;
        movementTargetX = 0.0;
        movementTargetZ = 0.0;
        movementIntentSet = false;
        movementControllerActive = false;
    }

    private void tickMovementController() {
        if (!movementControllerActive) return;

        Vec3 current = getDeltaMovement();
        // A collision can remove one velocity component. Rebase that axis so the
        // controller does not keep pushing against a wall with stale momentum.
        if (horizontalCollision) {
            if (Math.abs(current.x) < 0.015) controlledMotionX = 0.0;
            if (Math.abs(current.z) < 0.015) controlledMotionZ = 0.0;
        }

        double targetX = movementIntentSet ? movementTargetX : 0.0;
        double targetZ = movementIntentSet ? movementTargetZ : 0.0;
        double deltaX = targetX - controlledMotionX;
        double deltaZ = targetZ - controlledMotionZ;
        double deltaLength = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        double maxAcceleration = onGround() ? 0.035 : 0.018;
        if (deltaLength > maxAcceleration && deltaLength > 0.0001) {
            double scale = maxAcceleration / deltaLength;
            deltaX *= scale;
            deltaZ *= scale;
        }
        controlledMotionX += deltaX;
        controlledMotionZ += deltaZ;

        if (!movementIntentSet && Math.hypot(controlledMotionX, controlledMotionZ) < 0.012) {
            controlledMotionX = 0.0;
            controlledMotionZ = 0.0;
            movementControllerActive = false;
        }

        // The controller's acceleration state is independent of vanilla ground
        // friction, so the intended speed is stable while collision/gravity remain
        // entirely under the normal Minecraft entity physics.
        setDeltaMovement(controlledMotionX, current.y, controlledMotionZ);
    }

    @Override
    public void tick() {
        if (isRemoved()) return;
        if (isAlive()) tickMovementController();

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
