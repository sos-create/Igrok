package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class FakePlayerController {
    private final IgrokPlugin plugin;
    private IgrokServerPlayer bot;
    private EmbeddedChannel fakeChannel;

    FakePlayerController(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isSpawned() { return bot != null && !bot.isRemoved(); }
    boolean isBot(Player player) { return isSpawned() && player.getUniqueId().equals(bot.getUUID()); }
    Location location() { return isSpawned() ? bot.getBukkitEntity().getLocation().clone() : null; }
    Player bukkitPlayer() { return isSpawned() ? bot.getBukkitEntity() : null; }

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
        IgrokServerPlayer created = null;
        EmbeddedChannel channel = null;
        IgrokServerPlayer created = null;
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, name);
            Connection networkConnection = new Connection(PacketFlow.SERVERBOUND);
            // PlayerList.placeNewPlayer expects an active channel. A bare Connection has
            // no channel and crashes in setupInboundProtocol. EmbeddedChannel provides
            // a local, socket-free channel for this server-side fake player.
            EmbeddedChannel fakeChannel = new EmbeddedChannel(networkConnection);
            created = new IgrokServerPlayer(server, level, profile, networkConnection);
            created.setPos(location.getX(), location.getY(), location.getZ());
            created.setYRot(location.getYaw());
            created.setXRot(location.getPitch());
            created.setYHeadRot(location.getYaw());

            // A fake player has no client that needs a large terrain view. Keeping the
            // default server view distance makes Paper queue hundreds of chunks around
            // this NPC, needlessly stressing the server and exposing unrelated bad chunk
            // data far from the bot. Restrict it before registration and re-apply after
            // PlayerList installs the initial client settings.
            Player bukkitBot = created.getBukkitEntity();
            applySafeChunkDistances(bukkitBot);
            server.getPlayerList().placeNewPlayer(networkConnection, created,
                    CommonListenerCookie.createInitial(profile, false));
            created.restoreFakeListener();
            applySafeChunkDistances(bukkitBot);
            created.teleportTo(level, location.getX(), location.getY(), location.getZ(),
                    java.util.Set.of(), location.getYaw(), location.getPitch(), false);
            created.setHealth(created.getMaxHealth());

            if (!loadInventory(bukkitBot)) {
                bukkitBot.getInventory().setItem(0, new ItemStack(Material.STONE_AXE));
                bukkitBot.getInventory().setItem(1, new ItemStack(Material.STONE_PICKAXE));
                bukkitBot.getInventory().setItem(2, new ItemStack(Material.WOODEN_SWORD));
                bukkitBot.getInventory().setItem(3, new ItemStack(Material.BREAD, 8));
                bukkitBot.getInventory().setHeldItemSlot(2);
            }

            bot = created;
            fakeChannel = channel;
            plugin.getLogger().info("Igrok spawned at " + location.getWorld().getName()
                    + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ());
            return true;
        } catch (Throwable error) {
            // PlayerList.placeNewPlayer may have registered the entity before a later
            // initialization step fails. Always undo a partial spawn to avoid leaving
            // an invisible/orphaned player in the server player list.
            if (created != null && !created.isRemoved()) {
                try {
                    created.connection.onDisconnect(
                            new DisconnectionDetails(Component.literal("Igrok spawn failed")));
                } catch (Throwable cleanupError) {
                    plugin.getLogger().warning("Could not clean up partial Igrok spawn: "
                            + cleanupError.getMessage());
                    try {
                        created.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    } catch (Throwable ignored) { }
                }
            }
            if (channel != null) {
                try { channel.finishAndReleaseAll(); }
                catch (Throwable cleanupError) {
                    plugin.getLogger().warning("Could not close failed Igrok channel: " + cleanupError.getMessage());
                }
            }
            plugin.getLogger().severe("Could not spawn Igrok: " + error.getMessage());
            error.printStackTrace();
            return false;
        }
    }

    private void applySafeChunkDistances(Player player) {
        // Paper accepts values from 2 to 32. Two chunks are enough for the bot's
        // local sensing radius while avoiding a 15-32 chunk load radius for an NPC.
        player.setViewDistance(2);
        player.setSendViewDistance(2);
        player.setSimulationDistance(2);
    }

    void saveInventory() {
        Player player = bukkitPlayer();
        if (player == null) return;
        File file = new File(plugin.getDataFolder(), "bot-inventory.yml");
        File temporary = new File(plugin.getDataFolder(), "bot-inventory.yml.tmp");
        YamlConfiguration yaml = new YamlConfiguration();
        PlayerInventory inventory = player.getInventory();
        yaml.set("storage", Arrays.asList(inventory.getStorageContents()));
        yaml.set("armor", Arrays.asList(inventory.getArmorContents()));
        yaml.set("extra", Arrays.asList(inventory.getExtraContents()));
        yaml.set("health", player.getHealth());
        yaml.set("food", player.getFoodLevel());
        yaml.set("saturation", player.getSaturation());
        yaml.set("level", player.getLevel());
        yaml.set("exp", player.getExp());
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) return;
            yaml.save(temporary);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save Igrok inventory: " + error.getMessage());
        }
    }

    private boolean loadInventory(Player player) {
        File file = new File(plugin.getDataFolder(), "bot-inventory.yml");
        if (!file.isFile()) return false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        PlayerInventory inventory = player.getInventory();
        setItems(inventory.getStorageContents().length, yaml.getList("storage"), inventory::setStorageContents);
        setItems(inventory.getArmorContents().length, yaml.getList("armor"), inventory::setArmorContents);
        setItems(inventory.getExtraContents().length, yaml.getList("extra"), inventory::setExtraContents);
        player.setHealth(Math.max(1.0, Math.min(player.getMaxHealth(), yaml.getDouble("health", player.getMaxHealth()))));
        player.setFoodLevel(Math.max(0, Math.min(20, yaml.getInt("food", 20))));
        player.setSaturation(Math.max(0.0F, Math.min(20.0F, (float) yaml.getDouble("saturation", 5.0))));
        player.setLevel(Math.max(0, yaml.getInt("level", 0)));
        player.setExp(Math.max(0.0F, Math.min(1.0F, (float) yaml.getDouble("exp", 0.0))));
        return true;
    }

    private void setItems(int size, List<?> saved, java.util.function.Consumer<ItemStack[]> setter) {
        if (saved == null) return;
        ItemStack[] contents = new ItemStack[size];
        for (int i = 0; i < Math.min(size, saved.size()); i++) {
            Object item = saved.get(i);
            if (item instanceof ItemStack stack) contents[i] = stack.clone();
        }
        setter.accept(contents);
    }

    void remove() {
        saveInventory();
        IgrokServerPlayer current = bot;
        bot = null;
        if (current == null || current.isRemoved()) return;
        try {
            current.connection.onDisconnect(new DisconnectionDetails(Component.literal("Igrok removed")));
        } catch (Throwable error) {
            plugin.getLogger().warning("Could not cleanly remove Igrok: " + error.getMessage());
            current.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        } finally {
            EmbeddedChannel channel = fakeChannel;
            fakeChannel = null;
            if (channel != null) {
                try { channel.finishAndReleaseAll(); }
                catch (Throwable error) {
                    plugin.getLogger().warning("Could not close Igrok channel: " + error.getMessage());
                }
            }
        }
    }
}
