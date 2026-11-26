package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Locale;

public final class IgrokPlugin extends JavaPlugin implements Listener {
    private FakePlayerController controller;
    private ChatDialogue dialogue;
    private HouseBuilder houseBuilder;
    private AgentBrain brain;
    private AgentMemory memory;
    private BukkitTask respawnTask;
    private BukkitTask brainTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        controller = new FakePlayerController(this);
        houseBuilder = new HouseBuilder(this);
        memory = new AgentMemory(new File(getDataFolder(), "memory.yml"));
        memory.load();
        brain = new AgentBrain(this, memory);
        dialogue = new ChatDialogue(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(dialogue, this);
        brainTask = Bukkit.getScheduler().runTaskTimer(this, brain::tick, 1L, 1L);

        if (getConfig().getBoolean("auto-spawn", true)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!controller.isSpawned()) spawnAtDefaultWorldSpawn();
            });
        }
        getLogger().info("Igrok 0.3.0 cognitive agent enabled for Paper 1.21.4.");
    }

    @Override
    public void onDisable() {
        if (respawnTask != null) respawnTask.cancel();
        if (brainTask != null) brainTask.cancel();
        if (brain != null) brain.stop();
        if (houseBuilder != null) houseBuilder.cancel(false);
        if (memory != null) memory.save();
        if (controller != null) controller.remove();
    }

    private boolean spawnAtDefaultWorldSpawn() {
        String configuredWorld = getConfig().getString("spawn-world", "");
        World world = configuredWorld == null || configuredWorld.isBlank()
                ? Bukkit.getWorlds().stream().findFirst().orElse(null)
                : Bukkit.getWorld(configuredWorld);
        if (world == null) {
            getLogger().severe("Igrok spawn world was not found. Check spawn-world in config.yml.");
            return false;
        }
        Location spawn = world.getSpawnLocation().clone();
        spawn.setX(spawn.getBlockX() + 0.5);
        spawn.setZ(spawn.getBlockZ() + 0.5);
        return controller.spawn(spawn, getConfig().getString("bot-name", "Igrok"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("igrok")) return false;
        if (!sender.hasPermission("igrok.admin")) {
            sender.sendMessage("§cНедостаточно прав.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            String status = controller.isSpawned() ? "§aIgrok находится в мире." : "§eIgrok сейчас не создан.";
            if (houseBuilder.isBuilding()) status += " §6Он строит дом.";
            status += " §7Мозг: " + brain.state();
            sender.sendMessage(status);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "spawn" -> {
                if (controller.isSpawned()) sender.sendMessage("§eIgrok уже находится в мире.");
                else if (spawnAtDefaultWorldSpawn()) sender.sendMessage("§aIgrok появился на точке возрождения мира.");
                else sender.sendMessage("§cНе удалось создать Igrok. Подробности в консоли.");
            }
            case "remove" -> {
                brain.stop();
                houseBuilder.cancel(false);
                controller.remove();
                sender.sendMessage("§aIgrok удалён.");
            }
            case "build" -> {
                if (!controller.isSpawned()) {
                    sender.sendMessage("§cСначала создай бота: /igrok spawn");
                    return true;
                }
                Location anchor = sender instanceof Player player ? player.getLocation() : controller.location();
                Player actor = sender instanceof Player player ? player : controller.bukkitPlayer();
                if (houseBuilder.start(anchor, actor)) {
                    sender.sendMessage("§aIgrok начал строить дом. Прогресс будет виден в чате.");
                } else {
                    sender.sendMessage("§eIgrok не смог начать строительство. Проверь сообщение в чате.");
                }
            }
            case "cancelbuild" -> {
                if (!houseBuilder.isBuilding()) sender.sendMessage("§eСейчас строительство не выполняется.");
                else {
                    houseBuilder.cancel(true);
                    sender.sendMessage("§aСтроительство остановлено.");
                }
            }
            case "brain" -> {
                sender.sendMessage("§6Характер Igrok: §f" + brain.personalityDescription());
                sender.sendMessage("§6Память: §f" + memory.totalMined() + " добытых блоков; состояние: " + brain.state());
            }
            case "follow" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("§cИз консоли используйте /igrok follow <ник>.");
                    return true;
                }
                brain.follow(player);
                sender.sendMessage("§aIgrok будет следовать за тобой.");
            }
            case "mine" -> {
                String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "wood";
                if (!brain.requestMining(mode)) sender.sendMessage("§cИспользование: /igrok mine <wood|stone>");
                else sender.sendMessage("§aIgrok отправился добывать ресурсы.");
            }
            case "attack" -> {
                if (!brain.attackNearest()) sender.sendMessage("§eПодходящих враждебных мобов поблизости нет.");
            }
            case "explore" -> {
                brain.exploreNow();
                sender.sendMessage("§aIgrok отправился исследовать окрестности.");
            }
            case "stop" -> {
                brain.stop();
                sender.sendMessage("§aIgrok остановился.");
            }
            default -> sender.sendMessage("§eИспользование: /igrok <spawn|remove|status|build|cancelbuild|brain|follow|mine|attack|explore|stop>");
        }
        return true;
    }

    @EventHandler
    public void onBotDeath(PlayerDeathEvent event) {
        if (!controller.isBot(event.getEntity())) return;
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        brain.stop();
        houseBuilder.cancel(false);
        if (!getConfig().getBoolean("auto-respawn", true)) return;
        if (respawnTask != null) respawnTask.cancel();
        respawnTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            controller.remove();
            spawnAtDefaultWorldSpawn();
        }, 40L);
    }

    FakePlayerController controller() { return controller; }
    HouseBuilder houseBuilder() { return houseBuilder; }
    AgentBrain brain() { return brain; }
}
