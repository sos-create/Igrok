package ru.soscreate.igrok;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.StringJoiner;

/**
 * Runtime knowledge of the recipes registered by the exact Paper server version.
 * No recipe list is hard-coded: datapacks and server-registered recipes are indexed too.
 * Crafting-grid recipes are exposed to SurvivalController; furnace processing remains
 * handled by the survival furnace controller.
 */
final class RecipeKnowledge {
    private final Map<Material, List<Recipe>> byResult = new EnumMap<>(Material.class);
    private List<Recipe> allRecipes = List.of();
    private List<Recipe> craftingRecipes = List.of();
    private final Map<String, Integer> recipeFamilies = new java.util.LinkedHashMap<>();
    private int registeredRecipeCount;
    private boolean loaded;

    void refresh() {
        byResult.clear();
        recipeFamilies.clear();
        List<Recipe> all = new ArrayList<>();
        List<Recipe> crafting = new ArrayList<>();
        int registered = 0;
        Iterator<Recipe> iterator = Bukkit.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            if (recipe == null) continue;
            registered++;
            all.add(recipe);
            recipeFamilies.merge(recipe.getClass().getSimpleName(), 1, Integer::sum);
            ItemStack result = recipe.getResult();
            if (result == null || result.getType().isAir()) continue;
            byResult.computeIfAbsent(result.getType(), ignored -> new ArrayList<>()).add(recipe);
            if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
                crafting.add(recipe);
            }
        }
        allRecipes = List.copyOf(all);
        craftingRecipes = List.copyOf(crafting);
        registeredRecipeCount = registered;
        loaded = true;
    }

    boolean isLoaded() { return loaded; }
    int registeredRecipeCount() { return registeredRecipeCount; }
    int craftingRecipeCount() { return craftingRecipes.size(); }
    List<Recipe> allRecipes() { return allRecipes; }
    List<Recipe> craftingRecipes() { return craftingRecipes; }
    Map<String, Integer> recipeFamilies() { return Map.copyOf(recipeFamilies); }
    List<Recipe> recipesFor(Material result) {
        return result == null ? List.of() : byResult.getOrDefault(result, List.of());
    }

    boolean knowsRecipeFor(Material result) {
        return result != null && byResult.containsKey(result);
    }

    /** Compact explanation for AI diagnostics or natural-language answers. */
    String knowledgeSummary() {
        if (!loaded) return "Ещё загружаю рецепты сервера.";
        return "Знаю " + registeredRecipeCount + " рецептов сервера, включая "
                + craftingRecipes.size() + " рецептов верстака и " + recipeFamilies.size()
                + " типов обработки. Для предметов определяю назначение и ингредиенты; "
                + "разделяю инструменты, защиту, строительные материалы, рабочие станции, "
                + "хранение, земледелие, пищу и компоненты автоматики. "
                + "Крафт выбираю по дефициту текущего проекта, а не ради случайного запаса.";
    }

    /** Practical role classification shared by perception and planning. */
    String purpose(Material material) {
        return BlockKnowledge.purpose(material);
    }

    /**
     * Explains the first supported crafting recipe using ingredient choices
     * registered by this Paper server. Alternatives are represented by the
     * first valid choice; the actual craft still validates the full recipe.
     */
    String recipeExplanation(Material result) {
        List<Recipe> recipes = recipesFor(result);
        for (Recipe recipe : recipes) {
            Map<Material, Integer> ingredients = new LinkedHashMap<>();
            if (recipe instanceof ShapelessRecipe shapeless) {
                for (RecipeChoice choice : shapeless.getChoiceList()) addChoice(ingredients, choice, 1);
            } else if (recipe instanceof ShapedRecipe shaped) {
                Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
                for (String row : shaped.getShape()) {
                    for (int i = 0; i < row.length(); i++) {
                        RecipeChoice choice = choices.get(row.charAt(i));
                        if (choice != null) addChoice(ingredients, choice, 1);
                    }
                }
            } else {
                continue;
            }
            if (ingredients.isEmpty()) continue;
            StringJoiner joiner = new StringJoiner(", ");
            ingredients.forEach((material, amount) -> joiner.add(amount + " × "
                    + BlockPerception.russianName(material)));
            return "Для " + BlockPerception.russianName(result) + " требуется: " + joiner + ".";
        }
        return knowsRecipeFor(result)
                ? "Рецепт зарегистрирован сервером, но бот пока не выполняет этот тип обработки автоматически."
                : "На этом сервере не найден известный рецепт для этого предмета.";
    }

    private void addChoice(Map<Material, Integer> ingredients, RecipeChoice choice, int amount) {
        if (choice == null) return;
        ItemStack representative = choice.getItemStack();
        if (representative == null || representative.getType().isAir()) return;
        ingredients.merge(representative.getType(), amount, Integer::sum);
    }
}
