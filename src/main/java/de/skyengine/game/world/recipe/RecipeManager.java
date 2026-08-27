package de.skyengine.game.world.recipe;

import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Globale, nach dem Item-Bootstrap unveraenderliche Rezept- und Brennstoffverwaltung. */
public final class RecipeManager {

    public static final Identifier CRAFTING = Identifier.of("crafting");
    public static final Identifier FURNACE = Identifier.of("furnace");
    public static final Identifier SOLID_FUEL = Identifier.of("solid_fuel");

    private static final Logger LOGGER = LogManager.getLogger(RecipeManager.class.getName());
    private static RecipeManager INSTANCE = empty();

    private record ShapeKey(Identifier type, int width, int height, String occupancy) {}
    private record CellItem(int cell, Item item) {}

    private static final class ShapedBucket {
        final Map<CellItem, List<ShapedCraftingRecipe>> postings = new HashMap<>();
    }
    private static final class ShapelessBucket {
        final Map<Item, List<ShapelessCraftingRecipe>> postings = new HashMap<>();
    }
    private static final class ProcessingBucket {
        final Map<Item, List<ProcessingRecipe>> postings = new HashMap<>();
    }

    private final Map<ShapeKey, ShapedBucket> shaped = new HashMap<>();
    private final Map<Identifier, Map<Integer, ShapelessBucket>> shapeless = new HashMap<>();
    private final Map<Identifier, ProcessingBucket> processing = new HashMap<>();
    private final FuelRegistry fuels = new FuelRegistry();
    private final Set<String> warnedConflicts = new HashSet<>();
    private int lastCandidateCount;

    RecipeManager() {}

    public static void bootstrap() {
        List<ContentSource> sources = ContentSources.all();
        ItemTags tags = ItemTags.load(sources);
        RecipeManager manager = new RecipeManager();
        RecipeLoader.load(sources, tags, manager);
        INSTANCE = manager;
        LOGGER.info(manager.recipeCount() + " Rezepte kompiliert");
    }

    public static RecipeManager get() { return INSTANCE; }
    public FuelRegistry fuels() { return this.fuels; }

    void build(Map<Identifier, CraftingRecipe> crafting, Map<Identifier, ProcessingRecipe> processes,
               List<FuelRegistry.Fuel> fuels) {
        for (CraftingRecipe recipe : crafting.values()) {
            if (recipe instanceof ShapedCraftingRecipe shapedRecipe) this.addShaped(shapedRecipe);
            else if (recipe instanceof ShapelessCraftingRecipe shapelessRecipe) this.addShapeless(shapelessRecipe);
        }
        for (ProcessingRecipe recipe : processes.values()) this.addProcessing(recipe);
        for (FuelRegistry.Fuel fuel : fuels) this.fuels.add(fuel);
        sortAll();
    }

    public CraftingRecipe findCrafting(Identifier type, CraftingGrid grid) {
        this.lastCandidateCount = 0;
        NormalizedGrid normalized = NormalizedGrid.of(grid);
        if (normalized.width() == 0) return null;
        List<CraftingRecipe> matches = new ArrayList<>();

        ShapedBucket shapedBucket = this.shaped.get(new ShapeKey(type, normalized.width(),
                normalized.height(), normalized.occupancy()));
        if (shapedBucket != null) {
            List<ShapedCraftingRecipe> candidates = shortestShaped(shapedBucket, normalized);
            if (candidates != null) {
                this.lastCandidateCount += candidates.size();
                for (ShapedCraftingRecipe recipe : candidates) {
                if (recipe.matches(normalized, false) || recipe.matches(normalized, true)) matches.add(recipe);
                }
            }
        }

        int occupied = 0;
        for (ItemStack stack : normalized.stacks()) if (!stack.isEmpty()) occupied++;
        ShapelessBucket shapelessBucket = this.shapeless.getOrDefault(type, Map.of()).get(occupied);
        if (shapelessBucket != null) {
            List<ShapelessCraftingRecipe> candidates = shortestShapeless(shapelessBucket, normalized);
            if (candidates != null) {
                this.lastCandidateCount += candidates.size();
                for (ShapelessCraftingRecipe recipe : candidates) {
                if (recipe.matches(normalized)) matches.add(recipe);
                }
            }
        }
        return choose(matches, "craft:" + signature(normalized));
    }

    int lastCandidateCount() { return this.lastCandidateCount; }

    public ProcessingRecipe findProcessing(Identifier type, List<ItemStack> inputs) {
        ProcessingBucket bucket = this.processing.get(type);
        if (bucket == null) return null;
        List<ProcessingRecipe> candidates = null;
        for (ItemStack stack : inputs) {
            if (stack == null || stack.isEmpty()) continue;
            List<ProcessingRecipe> posting = bucket.postings.get(stack.getItem());
            if (posting == null) return null;
            if (candidates == null || posting.size() < candidates.size()) candidates = posting;
        }
        if (candidates == null) return null;
        List<ProcessingRecipe> matches = new ArrayList<>();
        for (ProcessingRecipe recipe : candidates) if (recipe.matches(inputs)) matches.add(recipe);
        return chooseProcessing(matches, "process:" + type + ':' + inputs);
    }

    private void addShaped(ShapedCraftingRecipe recipe) {
        this.addShapedVariant(recipe, false);
        this.addShapedVariant(recipe, true);
    }

    private void addShapedVariant(ShapedCraftingRecipe recipe, boolean mirrored) {
        StringBuilder occupancy = new StringBuilder(recipe.ingredients().size());
        for (int y = 0; y < recipe.height(); y++) for (int x = 0; x < recipe.width(); x++) {
            int patternX = mirrored ? recipe.width() - 1 - x : x;
            occupancy.append(recipe.ingredients().get(y * recipe.width() + patternX) == null ? '0' : '1');
        }
        ShapeKey key = new ShapeKey(recipe.recipeType(), recipe.width(), recipe.height(), occupancy.toString());
        ShapedBucket bucket = this.shaped.computeIfAbsent(key, ignored -> new ShapedBucket());
        for (int cell = 0; cell < recipe.ingredients().size(); cell++) {
            int x = cell % recipe.width(), y = cell / recipe.width();
            int patternX = mirrored ? recipe.width() - 1 - x : x;
            Ingredient ingredient = recipe.ingredients().get(y * recipe.width() + patternX);
            if (ingredient == null) continue;
            for (Item item : ingredient.acceptedItems()) {
                List<ShapedCraftingRecipe> posting = bucket.postings.computeIfAbsent(
                        new CellItem(cell, item), ignored -> new ArrayList<>());
                if (!posting.contains(recipe)) posting.add(recipe);
            }
        }
    }

    private void addShapeless(ShapelessCraftingRecipe recipe) {
        ShapelessBucket bucket = this.shapeless.computeIfAbsent(recipe.recipeType(), ignored -> new HashMap<>())
                .computeIfAbsent(recipe.ingredients().size(), ignored -> new ShapelessBucket());
        Set<Item> indexed = new LinkedHashSet<>();
        for (Ingredient ingredient : recipe.ingredients()) indexed.addAll(ingredient.acceptedItems());
        for (Item item : indexed) bucket.postings.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
    }

    private void addProcessing(ProcessingRecipe recipe) {
        ProcessingBucket bucket = this.processing.computeIfAbsent(recipe.recipeType(), ignored -> new ProcessingBucket());
        Set<Item> indexed = new LinkedHashSet<>();
        for (ProcessingRecipe.CountedIngredient ingredient : recipe.inputs()) {
            indexed.addAll(ingredient.ingredient().acceptedItems());
        }
        for (Item item : indexed) bucket.postings.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
    }

    private void sortAll() {
        Comparator<CraftingRecipe> craftingComparator = RecipeManager::compareRecipes;
        this.shaped.values().forEach(bucket -> bucket.postings.values().forEach(list -> list.sort(craftingComparator)));
        this.shapeless.values().forEach(map -> map.values().forEach(bucket ->
                bucket.postings.values().forEach(list -> list.sort(craftingComparator))));
        Comparator<ProcessingRecipe> processComparator = (a, b) -> compare(a.priority(), a.loadOrder(), a.id(),
                b.priority(), b.loadOrder(), b.id());
        this.processing.values().forEach(bucket -> bucket.postings.values().forEach(list -> list.sort(processComparator)));
    }

    private static List<ShapedCraftingRecipe> shortestShaped(ShapedBucket bucket, NormalizedGrid grid) {
        List<ShapedCraftingRecipe> shortest = null;
        for (int i = 0; i < grid.stacks().length; i++) {
            ItemStack stack = grid.stacks()[i];
            if (stack.isEmpty()) continue;
            List<ShapedCraftingRecipe> posting = bucket.postings.get(new CellItem(i, stack.getItem()));
            if (posting == null) return null;
            if (shortest == null || posting.size() < shortest.size()) shortest = posting;
        }
        return shortest;
    }

    private static List<ShapelessCraftingRecipe> shortestShapeless(ShapelessBucket bucket, NormalizedGrid grid) {
        List<ShapelessCraftingRecipe> shortest = null;
        for (ItemStack stack : grid.stacks()) {
            if (stack.isEmpty()) continue;
            List<ShapelessCraftingRecipe> posting = bucket.postings.get(stack.getItem());
            if (posting == null) return null;
            if (shortest == null || posting.size() < shortest.size()) shortest = posting;
        }
        return shortest;
    }

    private CraftingRecipe choose(List<CraftingRecipe> matches, String signature) {
        if (matches.isEmpty()) return null;
        matches.sort(RecipeManager::compareRecipes);
        if (matches.size() > 1 && this.warnedConflicts.add(signature)) {
            LOGGER.warning("Mehrdeutiges Rezept " + signature + ": " + matches.get(0).id()
                    + " gewinnt vor " + matches.get(1).id());
        }
        return matches.getFirst();
    }

    private ProcessingRecipe chooseProcessing(List<ProcessingRecipe> matches, String signature) {
        if (matches.isEmpty()) return null;
        matches.sort((a, b) -> compare(a.priority(), a.loadOrder(), a.id(), b.priority(), b.loadOrder(), b.id()));
        if (matches.size() > 1 && this.warnedConflicts.add(signature)) {
            LOGGER.warning("Mehrdeutiges Maschinenrezept " + signature + ": " + matches.get(0).id()
                    + " gewinnt vor " + matches.get(1).id());
        }
        return matches.getFirst();
    }

    static int compare(int priorityA, long orderA, Identifier idA,
                       int priorityB, long orderB, Identifier idB) {
        int priority = Integer.compare(priorityB, priorityA);
        if (priority != 0) return priority;
        int order = Long.compare(orderB, orderA);
        if (order != 0) return order;
        return idA.toString().compareTo(idB.toString());
    }

    private static int compareRecipes(CraftingRecipe a, CraftingRecipe b) {
        return compare(a.priority(), a.loadOrder(), a.id(), b.priority(), b.loadOrder(), b.id());
    }

    private static String signature(NormalizedGrid grid) {
        StringBuilder out = new StringBuilder(grid.occupancy());
        for (ItemStack stack : grid.stacks()) out.append('/').append(stack.isEmpty() ? "-" : stack.getItem().getId());
        return out.toString();
    }

    private int recipeCount() {
        Set<Identifier> ids = new HashSet<>();
        this.shaped.values().forEach(bucket -> bucket.postings.values().forEach(list -> list.forEach(r -> ids.add(r.id()))));
        this.shapeless.values().forEach(map -> map.values().forEach(bucket ->
                bucket.postings.values().forEach(list -> list.forEach(r -> ids.add(r.id())))));
        this.processing.values().forEach(bucket -> bucket.postings.values().forEach(list -> list.forEach(r -> ids.add(r.id()))));
        return ids.size();
    }

    private static RecipeManager empty() { return new RecipeManager(); }
}
