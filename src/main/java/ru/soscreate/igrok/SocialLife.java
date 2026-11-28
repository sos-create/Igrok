package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Social layer for Igrok as an independent world participant.
 *
 * It does not replace the brain or memory: it adds contextual speech,
 * bot-initiated invitations, and relationship events to the existing
 * compact per-player memory.
 */
final class SocialLife implements Listener {
    private enum Invitation {
        FOOD,
        EXPLORE
    }

    private record PendingInvitation(Invitation type, long expiresAt) { }

    private final IgrokPlugin plugin;
    private final Map<UUID, Long> nextAmbientAt = new HashMap<>();
    private final Map<UUID, Long> nextInitiativeAt = new HashMap<>();
    private final Map<UUID, Long> giftTrustCooldown = new HashMap<>();
    private final Map<UUID, PendingInvitation> pending = new HashMap<>();

    SocialLife(IgrokPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called only at the existing low-frequency social scan, never every tick. */
    void tick(Player bot) {
        if (bot == null || !bot.isOnline()
                || !plugin.getConfig().getBoolean("chat.social.enabled", true)
                || !plugin.getConfig().getBoolean("chat.social.ambient-enabled", true)
                || !plugin.brain().isSociallyAvailable()) {
            return;
        }

        long now = System.currentTimeMillis();
        Player nearby = nearestPlayer(bot);
        expirePending(now);
        if (nearby == null) return;

        UUID playerId = nearby.getUniqueId();
        plugin.brain().memory().rememberPlayerName(playerId, nearby.getName());

        long nextAmbient = nextAmbientAt.computeIfAbsent(playerId,
                ignored -> now + randomBetween(45_000L, 90_000L));
        if (now < nextAmbient) return;

        // Do not interrupt an active exchange with a second unsolicited line.
        if (plugin.brain().memory().isConversationActive(playerId, 90_000L)) {
            nextAmbientAt.put(playerId, now + 45_000L);
            return;
        }

        if (tryInitiative(bot, nearby, now)) {
            nextAmbientAt.put(playerId, now + 120_000L);
            return;
        }

        String line = contextualThought(bot, nearby);
        if (line != null) speak(playerId, line);
        long cooldown = Math.max(60L,
                plugin.getConfig().getLong("chat.social.ambient-cooldown-seconds", 150L)) * 1000L;
        nextAmbientAt.put(playerId, now + cooldown + ThreadLocalRandom.current().nextLong(0L, 45_001L));
        trimCaches();
    }

    private boolean tryInitiative(Player bot, Player player, long now) {
        UUID id = player.getUniqueId();
        if (pending.containsKey(id)) return false;

        long nextInvite = nextInitiativeAt.computeIfAbsent(id,
                ignored -> now + randomBetween(90_000L, 180_000L));
        if (now < nextInvite) return false;
        nextInitiativeAt.put(id, now + Math.max(180L,
                plugin.getConfig().getLong("chat.social.initiative-cooldown-seconds", 360L)) * 1000L);

        // A real need takes priority over a casual invitation.
        if (bot.getFoodLevel() <= 13 && !hasEdibleFood(bot)) {
            ask(player, Invitation.FOOD,
                    "У меня почти закончилась еда. Не поделишься чем-нибудь съедобным? Если сейчас нечего — ничего страшного, попробую добыть сам.");
            return true;
        }

        // Trust changes how readily Igrok proposes a joint trip. A damaged
        // relationship does not stop him from speaking, but he gives space.
        int trust = plugin.brain().memory().trust(id);
        if (trust < -3 || !plugin.brain().isSociallyAvailable()) return false;

        String invitation = trust >= 4
                ? "Я собираюсь немного исследовать окрестности. Составишь мне компанию? Интересно, что мы найдём вместе."
                : "Думаю пройтись и осмотреть новые места. Хочешь пойти со мной?";
        ask(player, Invitation.EXPLORE, invitation);
        return true;
    }

    private void ask(Player player, Invitation invitation, String text) {
        long expiresAt = System.currentTimeMillis() + 120_000L;
        pending.put(player.getUniqueId(), new PendingInvitation(invitation, expiresAt));
        speak(player.getUniqueId(), text);
    }

    /**
     * Consumes only a clear yes/no answer to an invitation started by Igrok.
     * Normal conversation and unrelated chat continue through ChatDialogue.
     */
    boolean handleReply(Player player, String rawMessage) {
        if (player == null || rawMessage == null) return false;
        UUID id = player.getUniqueId();
        PendingInvitation invitation = pending.get(id);
        if (invitation == null) return false;
        if (System.currentTimeMillis() > invitation.expiresAt()) {
            pending.remove(id);
            return false;
        }

        String normalized = rawMessage.toLowerCase(java.util.Locale.ROOT)
                .replace('ё', 'е').trim();
        boolean yes = hasAny(normalized, "да", "ага", "угу", "конечно", "пойдем",
                "пойдём", "давай", "согласен", "согласна", "хорошо", "без проблем");
        boolean no = hasAny(normalized, "нет", "не хочу", "не могу", "потом",
                "не сейчас", "отстань");
        if (!yes && !no) return false;

        pending.remove(id);
        AgentMemory memory = plugin.brain().memory();
        memory.rememberPlayerName(id, player.getName());
        memory.recordConversation(id, yes ? "[принял моё предложение]" : "[отклонил моё предложение]", false);

        String response;
        if (!yes) {
            response = invitation.type() == Invitation.FOOD
                    ? "Ладно, ничего страшного. Я сам попробую найти еду."
                    : "Хорошо, тогда пойду один или займусь чем-нибудь здесь. Увидимся позже.";
        } else {
            memory.adjustReputation(id, 1);
            if (invitation.type() == Invitation.EXPLORE) {
                plugin.brain().resumeForCommand();
                plugin.brain().follow(player);
                response = "Отлично. Я пойду рядом, но если замечу опасность, сначала позабочусь о безопасности.";
            } else {
                response = "Спасибо! Брось немного еды рядом со мной — я сам попробую её подобрать. Не нужно отдавать то, что тебе самому необходимо.";
            }
        }

        speak(id, response);
        return true;
    }

    private String contextualThought(Player bot, Player player) {
        if (bot.getFoodLevel() <= 15 && !hasEdibleFood(bot)) {
            return "Нужно подумать о запасе еды. Не хочу ждать, пока голод станет серьёзной проблемой.";
        }

        if (bot.getWorld().hasStorm()) {
            return choose(
                    "Погода испортилась. Я предпочитаю переждать сильный дождь под крышей, а потом продолжить путь.",
                    "С таким дождём видимость хуже. Хорошо, что сейчас не нужно далеко уходить.",
                    "Дождь напоминает, что убежище нужно не только ради красоты.");
        }

        long time = bot.getWorld().getTime() % 24_000L;
        if (time >= 13_000L && time <= 22_000L) {
            return choose(
                    "Скоро станет совсем темно. Я уже присматриваю безопасное место на ночь.",
                    "Ночь близко. Не люблю отправляться в незнакомую сторону без плана возвращения.",
                    "Кажется, пора проверить снаряжение и решить, где переждать ночь.");
        }

        Entity danger = bot.getNearbyEntities(9.0, 4.0, 9.0).stream()
                .filter(entity -> entity instanceof Monster)
                .findFirst().orElse(null);
        if (danger != null) {
            return "Я заметил неподалёку враждебное существо. Не стоит терять его из виду.";
        }

        int trust = plugin.brain().memory().trust(player.getUniqueId());
        if (trust >= 5) {
            return choose(
                    "Мы уже неплохо сработались. Мне нравится, что у каждого из нас остаётся своя свобода действий.",
                    "Рад, что мы можем помогать друг другу и при этом каждый занимается своими делами.",
                    "Я помню, как ты мне помогал. Такое отношение не забывается.");
        }
        if (trust <= -3) {
            return "Я пока предпочитаю держать небольшую дистанцию. Доверие появляется не сразу.";
        }

        String state = plugin.brain().state().toLowerCase(java.util.Locale.ROOT);
        if (state.contains("исслед") || state.contains("прогул")) {
            return choose(
                    "Я стараюсь запоминать дорогу и замечать места, которые могут пригодиться позже.",
                    "Интересно, что находится за следующим холмом. Но далеко без подготовки уходить не буду.",
                    "Мне нравится находить новые места, а не просто бесконечно стоять на одной точке.");
        }
        return choose(
                "Я тут осматриваюсь и обдумываю, чем заняться дальше. Не хочу тратить время без цели.",
                "Иногда полезно остановиться и посмотреть, что происходит вокруг.",
                "У меня есть свои планы на этот день. Сначала закончу текущее дело, потом решу, куда идти.",
                "Как тебе это место? Я бы ещё немного осмотрелся, прежде чем уходить.");
    }

    private Player nearestPlayer(Player bot) {
        double radius = Math.max(3.0, plugin.getConfig().getDouble("chat.social.radius", 7.0));
        return bot.getNearbyEntities(radius, 3.0, radius).stream()
                .filter(entity -> entity instanceof Player)
                .map(entity -> (Player) entity)
                .filter(player -> player.isOnline()
                        && player.getGameMode() != GameMode.SPECTATOR
                        && !player.getUniqueId().equals(bot.getUniqueId()))
                .min(java.util.Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(bot.getLocation())))
                .orElse(null);
    }

    private boolean hasEdibleFood(Player bot) {
        for (ItemStack stack : bot.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir() && stack.getType().isEdible()) return true;
        }
        return false;
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
        if (giver == null || !giver.isOnline() || giver.getWorld() != bot.getWorld()
                || giver.getLocation().distanceSquared(bot.getLocation()) > 20.25) return;

        long now = System.currentTimeMillis();
        if (now - giftTrustCooldown.getOrDefault(giverId, 0L) < 600_000L) return;
        giftTrustCooldown.put(giverId, now);

        Material type = itemEntity.getItemStack().getType();
        AgentMemory memory = plugin.brain().memory();
        memory.rememberPlayerName(giverId, giver.getName());
        memory.adjustReputation(giverId, type.isEdible() ? 2 : 1);
        memory.recordConversation(giverId, "[поделился со мной: "
                + BlockPerception.russianName(type) + "]", false);
        plugin.brain().rememberBotMessage(giverId,
                "Спасибо, " + giver.getName() + ". Я запомню, что ты поделился со мной " 
                        + BlockPerception.russianName(type) + ".");
        trimCaches();
    }

    private void speak(UUID playerId, String message) {
        if (message == null || message.isBlank()) return;
        plugin.brain().rememberBotMessage(playerId, message);
        String botName = plugin.getConfig().getString("bot-name", "Igrok");
        if (botName == null || botName.isBlank()) botName = "Igrok";
        Bukkit.broadcastMessage("§6" + botName + "§7: §f" + message);
    }

    private void expirePending(long now) {
        Iterator<Map.Entry<UUID, PendingInvitation>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expiresAt() < now) iterator.remove();
        }
    }

    private void trimCaches() {
        trim(nextAmbientAt);
        trim(nextInitiativeAt);
        trim(giftTrustCooldown);
        if (pending.size() > 128) {
            Iterator<UUID> iterator = pending.keySet().iterator();
            while (pending.size() > 128 && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void trim(Map<UUID, Long> map) {
        if (map.size() <= 128) return;
        Iterator<UUID> iterator = map.keySet().iterator();
        while (map.size() > 128 && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private long randomBetween(long min, long max) {
        return ThreadLocalRandom.current().nextLong(min, max + 1L);
    }

    private String choose(String... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    private boolean hasAny(String text, String... patterns) {
        for (String pattern : patterns) {
            if (text.equals(pattern) || text.startsWith(pattern + " ") || text.startsWith(pattern + ",")
                    || text.startsWith(pattern + ".") || text.startsWith(pattern + "!")) return true;
        }
        return false;
    }
}
