package ru.soscreate.igrok;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

/**
 * Practical affordance knowledge for Minecraft blocks.
 *
 * This class describes what a block is useful for, how Igrok can safely work
 * with it, and which tool or resource is required. It deliberately distinguishes
 * supported interactions from ideas the bot cannot currently perform.
 */
final class BlockKnowledge {
    private BlockKnowledge() { }

    private static final java.util.Map<String, Material> RUSSIAN_MATERIAL_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("поршень", Material.PISTON),
            java.util.Map.entry("липкий поршень", Material.STICKY_PISTON),
            java.util.Map.entry("блок редстоуна", Material.REDSTONE_BLOCK),
            java.util.Map.entry("редстоуновая пыль", Material.REDSTONE),
            java.util.Map.entry("повторитель", Material.REPEATER),
            java.util.Map.entry("наблюдатель", Material.OBSERVER),
            java.util.Map.entry("раздатчик", Material.DISPENSER),
            java.util.Map.entry("выбрасыватель", Material.DROPPER),
            java.util.Map.entry("воронка", Material.HOPPER),
            java.util.Map.entry("верстак", Material.CRAFTING_TABLE),
            java.util.Map.entry("печь", Material.FURNACE),
            java.util.Map.entry("плавильная печь", Material.BLAST_FURNACE),
            java.util.Map.entry("коптильня", Material.SMOKER),
            java.util.Map.entry("сундук", Material.CHEST),
            java.util.Map.entry("бочка", Material.BARREL),
            java.util.Map.entry("железная кирка", Material.IRON_PICKAXE),
            java.util.Map.entry("каменная кирка", Material.STONE_PICKAXE),
            java.util.Map.entry("деревянная кирка", Material.WOODEN_PICKAXE),
            java.util.Map.entry("кирка", Material.IRON_PICKAXE),
            java.util.Map.entry("железный топор", Material.IRON_AXE),
            java.util.Map.entry("каменный топор", Material.STONE_AXE),
            java.util.Map.entry("топор", Material.IRON_AXE),
            java.util.Map.entry("меч", Material.IRON_SWORD),
            java.util.Map.entry("щит", Material.SHIELD),
            java.util.Map.entry("факел", Material.TORCH),
            java.util.Map.entry("кровать", Material.WHITE_BED),
            java.util.Map.entry("хлеб", Material.BREAD),
            java.util.Map.entry("палка", Material.STICK),
            java.util.Map.entry("булыжник", Material.COBBLESTONE),
            java.util.Map.entry("дубовые доски", Material.OAK_PLANKS),
            java.util.Map.entry("доски", Material.OAK_PLANKS),
            java.util.Map.entry("бревно", Material.OAK_LOG),
            java.util.Map.entry("дубовое бревно", Material.OAK_LOG),
            java.util.Map.entry("уголь", Material.COAL),
            java.util.Map.entry("железный слиток", Material.IRON_INGOT),
            java.util.Map.entry("железо", Material.IRON_INGOT),
            java.util.Map.entry("красная пыль", Material.REDSTONE)
    );

    static Material findMaterialMention(String text) {
        if (text == null || text.isBlank()) return null;
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        for (String alias : RUSSIAN_MATERIAL_NAMES.keySet().stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
            if (normalized.contains(alias)) return RUSSIAN_MATERIAL_NAMES.get(alias);
        }
        for (Material material : Material.values()) {
            String id = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            if (id.length() >= 4 && normalized.contains(id)) return material;
        }
        return null;
    }

    static String quickAction(Material material) {
        if (material == null) return "назначение пока неизвестно";
        String name = material.name();

        if (isOre(name)) return oreAction(name);
        if (isLog(name)) return "срубить топором; брёвна переработать в доски и палки";
        if (name.endsWith("_LEAVES")) return "можно разобрать после рубки дерева; искать саженцы и еду";
        if (isCrop(name)) return "проверить зрелость; собрать урожай и пересадить имеющимися семенами";
        if (name.equals("FARMLAND")) return "почва для посадки культур; не выкапывать без необходимости";
        if (name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("BARREL")
                || name.endsWith("SHULKER_BOX")) return "контейнер: открыть по доступу и сложить только собственные излишки";
        if (name.equals("FURNACE") || name.equals("BLAST_FURNACE")) {
            return "переплавлять руды и сырьё; нужны топливо и входные материалы";
        }
        if (name.equals("SMOKER")) return "готовить пищу быстрее обычной печи; нужны топливо и сырая еда";
        if (name.equals("CRAFTING_TABLE")) return "изготовлять предметы по известным серверным рецептам";
        if (name.equals("BED") || name.endsWith("_BED")) return "спать ночью, чтобы пропустить опасное время";
        if (name.equals("WATER")) return "источник воды; учитывать течение и не идти в глубокую воду без причины";
        if (name.equals("LAVA") || name.equals("MAGMA_BLOCK") || name.equals("FIRE")
                || name.equals("SOUL_FIRE") || name.equals("CACTUS") || name.equals("POWDER_SNOW")) {
            return "опасность: обходить, не прокладывать через неё маршрут";
        }
        if (name.equals("STONE") || name.equals("DEEPSLATE") || name.equals("COBBLESTONE")) {
            return "добывать киркой только при потребности в камне, печи или строительстве";
        }
        if (name.equals("GRAVEL") || name.equals("SAND")) return "сыпучий блок: учитывать падение и возможное удушье";
        if (name.equals("CRAFTING_TABLE") || name.equals("FURNACE")) return "рабочая станция";
        if (name.equals("COMPOSTER")) return "перерабатывать растительные излишки в костную муку";
        if (name.equals("BEE_NEST") || name.equals("BEEHIVE")) return "дом пчёл: не ломать без инструмента с Шёлковым касанием";
        if (name.equals("ANVIL") || name.equals("CHIPPED_ANVIL") || name.equals("DAMAGED_ANVIL")) {
            return "ремонтировать и объединять снаряжение за опыт";
        }
        if (name.equals("SMITHING_TABLE")) return "улучшать снаряжение по кузнечным шаблонам и материалам";
        if (name.equals("ENCHANTING_TABLE")) return "зачаровывать предметы при наличии опыта и лазурита";
        if (name.equals("BREWING_STAND")) return "варить зелья при наличии ингредиентов и топлива";
        if (name.equals("STONECUTTER")) return "изготавливать варианты каменных строительных блоков";
        if (name.equals("GRINDSTONE")) return "снимать зачарования и ремонтировать одинаковые предметы";
        if (name.equals("LEVER") || name.endsWith("_BUTTON") || name.endsWith("_PRESSURE_PLATE")
                || name.equals("REDSTONE_TORCH") || name.equals("REDSTONE_WIRE")) {
            return "элемент редстоун-схемы: использовать только в запланированном механизме";
        }
        if (name.endsWith("_DOOR")) {
            return name.contains("IRON")
                    ? "дверь с редстоун-управлением; без питания не пытаться проходить сквозь неё"
                    : "при подходе попробовать открыть обычным взаимодействием, если это разрешено защитой";
        }
        if (name.endsWith("_TRAPDOOR") || name.endsWith("_FENCE_GATE")) {
            return "проход или преграда; проверить открытое состояние перед движением";
        }
        if (name.contains("PISTON") || name.equals("OBSERVER") || name.equals("REPEATER")
                || name.equals("COMPARATOR") || name.equals("DROPPER") || name.equals("DISPENSER")
                || name.equals("HOPPER")) {
            return "компонент автоматики; не тратить без конкретной схемы";
        }
        if (name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS")) return oreAction(name);
        return "оценить прочность, полезность и необходимость для текущего проекта перед добычей";
    }

    static String describe(Block block) {
        if (block == null) return "Блок не определён.";
        Material material = block.getType();
        String result = BlockPerception.russianName(material) + ": " + quickAction(material) + ".";
        if (block.getBlockData() instanceof Ageable ageable) {
            if (ageable.getAge() >= ageable.getMaximumAge()) {
                result += " Урожай созрел — можно собрать и пересадить.";
            } else {
                result += " Урожай ещё растёт (" + ageable.getAge() + "/"
                        + ageable.getMaximumAge() + "); пока не ломать.";
            }
        }
        return result;
    }

    static String purpose(Material material) {
        if (material == null) return "назначение неизвестно";
        String name = material.name();
        if (isOre(name) || isLog(name) || name.endsWith("_LEAVES")
                || name.equals("STONE") || name.equals("DEEPSLATE")
                || name.equals("COBBLESTONE")) return "сырьё";
        if (name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SWORD")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SHEARS")) {
            return "инструмент";
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("SHIELD")) {
            return "защита";
        }
        if (name.endsWith("_PLANKS") || name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                || name.endsWith("_FENCE") || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_WALL") || name.endsWith("_BRICKS")) return "строительство";
        if (name.contains("PISTON") || name.contains("REDSTONE") || name.equals("OBSERVER")
                || name.equals("REPEATER") || name.equals("COMPARATOR")
                || name.equals("DISPENSER") || name.equals("DROPPER") || name.equals("HOPPER")) {
            return "автоматика";
        }
        if (name.equals("CRAFTING_TABLE") || name.equals("FURNACE") || name.equals("BLAST_FURNACE")
                || name.equals("SMOKER") || name.equals("STONECUTTER") || name.equals("SMITHING_TABLE")
                || name.equals("ANVIL") || name.equals("GRINDSTONE") || name.equals("BREWING_STAND")) {
            return "рабочая станция";
        }
        if (name.equals("CHEST") || name.equals("TRAPPED_CHEST") || name.equals("BARREL")
                || name.endsWith("SHULKER_BOX")) return "хранение";
        if (isCrop(name) || name.equals("WHEAT_SEEDS") || name.equals("BEETROOT_SEEDS")) return "земледелие";
        if (name.endsWith("_BED") || name.equals("TORCH") || name.equals("LANTERN")) return "отдых и свет";
        if (name.equals("BREAD") || name.endsWith("_STEW") || name.endsWith("_SOUP")
                || name.startsWith("COOKED_") || name.equals("BAKED_POTATO")) return "пища";
        return "вспомогательный ресурс";
    }

    static Material planksForLog(Material log) {
        if (log == null) return null;
        String name = log.name();
        if (name.equals("BAMBOO_BLOCK") || name.equals("STRIPPED_BAMBOO_BLOCK")) {
            return Material.BAMBOO_PLANKS;
        }
        if (!(name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE"))) return null;
        if (name.startsWith("STRIPPED_")) name = name.substring("STRIPPED_".length());
        int separator = name.lastIndexOf('_');
        if (separator <= 0) return null;
        return Material.matchMaterial(name.substring(0, separator) + "_PLANKS");
    }

    private static boolean isCrop(String name) {
        return name.equals("WHEAT") || name.equals("CARROTS") || name.equals("POTATOES")
                || name.equals("BEETROOTS") || name.equals("NETHER_WART")
                || name.equals("COCOA") || name.equals("PUMPKIN_STEM")
                || name.equals("MELON_STEM") || name.equals("SWEET_BERRY_BUSH");
    }

    private static boolean isLog(String name) {
        return (name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE"))
                && !name.startsWith("STRIPPED_");
    }

    private static boolean isOre(String name) {
        return name.endsWith("_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    private static String oreAction(String name) {
        int tier;
        String tool;
        if (name.contains("DIAMOND") || name.contains("EMERALD") || name.contains("GOLD")
                || name.contains("REDSTONE") || name.equals("ANCIENT_DEBRIS")) {
            tier = name.equals("ANCIENT_DEBRIS") ? 5 : 4;
            tool = tier == 5 ? "алмазная кирка" : "железная кирка или лучше";
        } else if (name.contains("IRON") || name.contains("COPPER") || name.contains("LAPIS")) {
            tier = 3;
            tool = "каменная кирка или лучше";
        } else if (name.contains("COAL") || name.contains("QUARTZ") || name.contains("NETHER_GOLD")) {
            tier = 1;
            tool = "любая подходящая кирка";
        } else {
            tier = 1;
            tool = "кирка подходящего уровня";
        }
        return "добывать " + tool + "; минимальный уровень инструмента " + tier;
    }
}
