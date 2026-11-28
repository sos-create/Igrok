package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Relationship events for Igrok as an independent world participant.
 *
 * Igrok does not initiate invitations, food requests, greetings or ambient
 * conversations. This layer records player gifts and relationship changes;
 * ordinary conversation starts from player messages.
 */
final class SocialLife implements Listener {
    private final IgrokPlugin plugin;
    private final Map<UUID, Long> giftTrustCooldown = new HashMap<>();

    SocialLife(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    /** Low-frequency maintenance only. This routine never starts a conversation. */
    void tick(Player bot) {
        if (bot == null || !bot.isOnline()) return;
        trimCaches();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGiftPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player bot) || !plugin.controller().isSpawned()) return;
        Player actualBot = plugin.controller().bukkitPlayer();
        if (actualBot == null || !bot.getUniqueId().equals(actualBot.getUniqueId())) return;

        Item itemEntity = event.getItem();
        UUID giverId = itemEntity.getThrower();
        if (giverId == null || giverId.equals(bot.getUniqueId())) return;
        Player giver = Bukkit.getPlayer(giverId);
        // The item's thrower UUID is the authoritative gift attribution. The
        // giver may have moved away while the dropped item travelled to Igrok.
        if (giver == null || !giver.isOnline() || giver.getWorld() != bot.getWorld()) return;

        long now = System.currentTimeMillis();
        if (now - giftTrustCooldown.getOrDefault(giverId, 0L) < 600_000L) return;
        giftTrustCooldown.put(giverId, now);

        Material type = itemEntity.getItemStack().getType();
        AgentMemory memory = plugin.brain().memory();
        memory.rememberPlayerName(giverId, giver.getName());
        memory.adjustReputation(giverId, type.isEdible() ? 2 : 1);
        plugin.skills().gain("social", 1);
        plugin.skills().gain("cooperation", 1);
        memory.recordConversation(giverId, "[поделился со мной: "
                + BlockPerception.russianName(type) + "]", false);
        speak(giverId, "Спасибо, " + giver.getName() + ". Я запомню, что ты поделился со мной "
                + BlockPerception.russianName(type) + ".");
        trimCaches();
    }

    private void speak(UUID playerId, String message) {
        if (message == null || message.isBlank()) return;
        plugin.brain().rememberBotMessage(playerId, message);
        String botName = plugin.getConfig().getString("bot-name", "Igrok");
        if (botName == null || botName.isBlank()) botName = "Igrok";
        Player recipient = Bukkit.getPlayer(playerId);
        if (recipient != null && recipient.isOnline()) {
            recipient.sendMessage("§6" + botName + "§7: §f" + message);
            if (plugin.dialogue() != null) plugin.dialogue().recordBotReply(playerId, message);
        }
    }

    private void trimCaches() {
        if (giftTrustCooldown.size() <= 128) return;
        Iterator<UUID> iterator = giftTrustCooldown.keySet().iterator();
        while (giftTrustCooldown.size() > 128 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }
}
