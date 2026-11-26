package ru.soscreate.igrok;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.destroystokyo.paper.profile.ProfileProperty;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import java.util.Collection;
import java.util.List;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class FakePlayerController {
    private final IgrokPlugin plugin;
    private IgrokServerPlayer bot;
    private EmbeddedChannel fakeChannel;
    private List<ProfileProperty> skinProperties = List.of();

    FakePlayerController(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isSpawned() { return bot != null && !bot.isRemoved(); }
    boolean isBot(Player player) { return isSpawned() && player.getUniqueId().equals(bot.getUUID()); }
    Location location() { return isSpawned() ? bot.getBukkitEntity().getLocation().clone() : null; }
    Player bukkitPlayer() { return isSpawned() ? bot.getBukkitEntity() : null; }

    void setSkinProperties(Collection<ProfileProperty> properties) {
        if (properties == null || properties.isEmpty()) {
            skinProperties = List.of();
            return;
        }
        skinProperties = properties.stream()
                .filter(property -> "textures".equals(property.getName()))
                .filter(property -> property.getValue() != null && !property.getValue().isBlank())
                .filter(property -> property.getSignature() != null && !property.getSignature().isBlank())
                .toList();
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
        IgrokServerPlayer created = null;
        EmbeddedChannel channel = null;
        try {
            MinecraftServer server = ((CraftServer) Bukkit.getServer()).getServer();
            ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(uuid, name);
            // Preserve the official signed textures property for the normal player model.
            for (ProfileProperty skin : skinProperties) {
                profile.getProperties().put("textures",
                        new Property("textures", skin.getValue(), skin.getSignature()));
            }

            ClientInformation defaults = ClientInformation.createDefault();
            ClientInformation limitedClientInfo = new ClientInformation(
                    defaults.language(), 2, defaults.chatVisibility(), defaults.chatColors(),
                    defaults.modelCustomisation(), defaults.mainHand(), defaults.textFilteringEnabled(),
                    defaults.allowsListing(), defaults.particleStatus());
            CommonListenerCookie cookie = new CommonListenerCookie(profile, 0, limitedClientInfo, false);

            Connection networkConnection = new Connection(PacketFlow.SERVERBOUND);
            // PlayerList.placeNewPlayer expects an active channel. A bare Connection has
            // no channel and crashes in setupInboundProtocol. EmbeddedChannel provides
            // a local, socket-free channel for this server-side fake player.
            channel = new EmbeddedChannel(networkConnection);
            // The real ServerPlayer client settings must be restricted before registration,
            // because registration immediately creates player chunk tickets.
            created = new IgrokServerPlayer(server, level, profile, networkConnection, limitedClientInfo);
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
            server.getPlayerList().placeNewPlayer(networkConnection, created, cookie);
            created.restoreFakeListener();
            applySafeChunkDistances(bukkitBot);
            // Paper's login registration may place a new offline player at the world's
            // spawn position even though setPos was called before registration. Restore
            // the already-validated target only after PlayerList has finished registration.
            // The constructor already carries the 2-chunk ClientInformation, so this
            // relocation cannot request the server's full default view radius.
            if (!created.teleportTo(level, location.getX(), location.getY(), location.getZ(),
                    java.util.Set.of(), location.getYaw(), location.getPitch(), false)) {
                throw new IllegalStateException("Paper refused the validated Igrok spawn position");
            }
            // Ordinary survival identity: no operator, creative mode, invulnerability or starter kit.
            bukkitBot.setOp(false);
            bukkitBot.setGameMode(GameMode.SURVIVAL);
            bukkitBot.setInvulnerable(false);
            bukkitBot.setAllowFlight(false);
            bukkitBot.setCanPickupItems(true);
            bukkitBot.setCollidable(true);
            created.setHealth(created.getMaxHealth());

            if (!loadInventory(bukkitBot)) {
                bukkitBot.getInventory().clear();
                bukkitBot.setHealth(bukkitBot.getMaxHealth());
                bukkitBot.setFoodLevel(20);
                bukkitBot.setSaturation(5.0F);
                bukkitBot.setExhaustion(0.0F);
                bukkitBot.setLevel(0);
                bukkitBot.setExp(0.0F);
            }

            bot = created;
            fakeChannel = channel;
            plugin.getLogger().info("Igrok spawned at " + location.getWorld().getName()
                    + " " + location.getBlockX() + " " + location.getBlockY() + " " + location.getBlockZ()
                    + " [client-view=" + bukkitBot.getClientViewDistance()
                    + ", view=" + bukkitBot.getViewDistance()
                    + ", send-view=" + bukkitBot.getSendViewDistance()
                    + ", simulation=" + bukkitBot.getSimulationDistance()
                    + ", skin=" + (skinProperties.isEmpty() ? "default" : "custom") + "]");
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
        boolean dead = player.isDead() || player.getHealth() <= 0.0;
        // Persist the vanilla death result, not a stale pre-death inventory or zero HP.
        yaml.set("health", dead ? player.getMaxHealth() : player.getHealth());
        yaml.set("food", dead ? 20 : player.getFoodLevel());
        yaml.set("saturation", dead ? 5.0F : player.getSaturation());
        yaml.set("level", dead ? 0 : player.getLevel());
        yaml.set("exp", dead ? 0.0F : player.getExp());
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
        EmbeddedChannel channel = fakeChannel;
        fakeChannel = null;
        try {
            if (current != null && !current.isRemoved()) {
                try {
                    current.connection.onDisconnect(new DisconnectionDetails(Component.literal("Igrok removed")));
                } catch (Throwable error) {
                    plugin.getLogger().warning("Could not cleanly remove Igrok: " + error.getMessage());
                    current.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
            }
        } finally {
            if (channel != null) {
                try { channel.finishAndReleaseAll(); }
                catch (Throwable error) {
                    plugin.getLogger().warning("Could not close Igrok channel: " + error.getMessage());
                }
            }
        }
    }
}
