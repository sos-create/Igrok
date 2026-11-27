package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Runtime knowledge of the recipes registered by the exact Paper server version.
 * No recipe list is hard-coded: datapacks and server-registered recipes are indexed too.
 * Crafting-grid recipes are exposed to SurvivalController; furnace processing remains
 * handled by the survival furnace controller.
 */
final class RecipeKnowledge {
    private final Map<Material, List<Recipe>> byResult = new EnumMap<>(Material.class);
    private List<Recipe> craftingRecipes = List.of();
    private int registeredRecipeCount;
    private boolean loaded;

    void refresh() {
        byResult.clear();
        List<Recipe> crafting = new ArrayList<>();
        int registered = 0;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe == null) continue;
            registered++;
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir()) continue;
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) continue;
            crafting.add(recipe);
            byResult.computeIfAbsent(result.getType(), ignored -> new ArrayList<>()).add(recipe);
        }
        craftingRecipes = List.copyOf(crafting);
        registeredRecipeCount = registered;
        loaded = true;
    }

    boolean isLoaded() { return loaded; }
    int registeredRecipeCount() { return registeredRecipeCount; }
    int craftingRecipeCount() { return craftingRecipes.size(); }
    List<Recipe> craftingRecipes() { return craftingRecipes; }
    List<Recipe> recipesFor(Material result) {
        return result == null ? List.of() : byResult.getOrDefault(result, List.of());
    }

    /** Practical role classification used by goal selection and diagnostics. */
    String purpose(Material material) {
        if (material == null) return "неизвестное назначение";
        String name = material.name();
        if (name.contains("PISTON") || name.contains("REDSTONE") || name.contains("OBSERVER")
                || name.contains("REPEATER") || name.contains("COMPARATOR")
                || name.contains("DISPENSER") || name.contains("DROPPER") || name.contains("HOPPER")) {
            return "механизмы и редстоун";
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SWORD")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_SHEARS")) {
            return "инструменты и добыча";
        }
        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") || name.equals("SHIELD")) {
            return "защита";
        }
        if (name.endsWith("_PLANKS") || name.endsWith("_STAIRS") || name.endsWith("_SLAB")
                || name.endsWith("_FENCE") || name.endsWith("_DOOR") || name.endsWith("_TRAPDOOR")
                || name.endsWith("_WALL") || name.endsWith("_BRICKS")) {
            return "строительство";
        }
        if (name.equals("FURNACE") || name.equals("BLAST_FURNACE") || name.equals("SMOKER")
                || name.equals("CRAFTING_TABLE") || name.equals("CHEST") || name.equals("BARREL")) {
            return "рабочие станции и хранение";
        }
        if (name.equals("BREAD") || name.endsWith("_STEW") || name.endsWith("_SOUP")
                || name.equals("COOKIE") || name.equals("PUMPKIN_PIE")) {
            return "пища";
        }
        if (name.endsWith("_BED") || name.equals("TORCH") || name.equals("LANTERN")) {
            return "отдых и освещение";
        }
        return "универсальное применение";
    }
}
