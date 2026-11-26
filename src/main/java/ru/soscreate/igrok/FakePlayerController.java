package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class FakePlayerController {
    private final IgrokPlugin plugin;
    private IgrokServerPlayer bot;

    FakePlayerController(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isSpawned() {
        return bot != null && !bot.isRemoved();
    }

    boolean isBot(Player player) {
        return isSpawned() && player.getUniqueId().equals(bot.getUUID());
    }

    Location location() {
        return isSpawned() ? bot.getBukkitEntity().getLocation().clone() : null;
    }

    Player bukkitPlayer() {
        return isSpawned() ? bot.getBukkitEntity() : null;
    }

    boolean spawn(Location location, String configuredName) {
        if (isSpawned()) return true;
        String name = configuredName == null || configuredName.isBlank() ? "Igrok" : configuredName.trim();
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            plugin.getLogger().severe("bot-name must contain 3-16 English letters, digits or underscores.");
            return false;
        }
        if (Bukkit.getPlayerExact(name) != null) {
            plugin.getLogger().severe("A real player already uses the bot name " + name);
            return false;
        }
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, name);
            Connection networkConnection = new Connection(PacketFlow.SERVERBOUND);
            IgrokServerPlayer created = new IgrokServerPlayer(server, level, profile, networkConnection);
            created.setPos(location.getX(), location.getY(), location.getZ());
            created.setYRot(location.getYaw());
            created.setXRot(location.getPitch());
            created.setYHeadRot(location.getYaw());

            server.getPlayerList().placeNewPlayer(networkConnection, created,
                    CommonListenerCookie.createInitial(profile, false));
            created.restoreFakeListener();
            created.teleportTo(level, location.getX(), location.getY(), location.getZ(),
                    java.util.Set.of(), location.getYaw(), location.getPitch(), false);
            created.setHealth(created.getMaxHealth());
            bot = created;
            plugin.getLogger().info("Igrok spawned at " + location.getWorld().getName()
                    + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ());
            return true;
        } catch (Throwable error) {
            plugin.getLogger().severe("Could not spawn Igrok: " + error.getMessage());
            error.printStackTrace();
            return false;
        }
    }

    void remove() {
        IgrokServerPlayer current = bot;
        bot = null;
        if (current == null || current.isRemoved()) return;
        try {
            current.connection.onDisconnect(new DisconnectionDetails(Component.literal("Igrok removed")));
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not cleanly remove Igrok: " + error.getMessage());
            current.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
    }
}
