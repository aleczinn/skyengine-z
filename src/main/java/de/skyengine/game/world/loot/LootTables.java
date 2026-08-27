package de.skyengine.game.world.loot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.skyengine.game.world.block.Block;
import de.skyengine.game.world.block.Identifier;
import de.skyengine.game.world.block.content.ContentSource;
import de.skyengine.game.world.block.content.ContentSources;
import de.skyengine.game.world.block.registry.Registries;
import de.skyengine.game.world.block.state.BlockState;
import de.skyengine.game.world.block.state.Property;
import de.skyengine.game.world.item.Enchantment;
import de.skyengine.game.world.item.Enchantments;
import de.skyengine.game.world.item.Item;
import de.skyengine.game.world.item.ItemStack;
import de.skyengine.game.world.item.ToolItem;
import de.skyengine.game.world.item.ToolTier;
import de.skyengine.game.world.item.ToolType;
import de.skyengine.utils.logging.LogManager;
import de.skyengine.utils.logging.Logger;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lädt und kompiliert den streng validierten, blockrelevanten Vanilla-Teilumfang. */
public final class LootTables {

    private static final Logger LOGGER = LogManager.getLogger(LootTables.class.getName());
    private static final Map<Block, LootTable> TABLES = new IdentityHashMap<>();

    public static void bootstrap() {
        TABLES.clear();
        List<String> errors = new ArrayList<>();
        for (ContentSource source : ContentSources.all()) loadSource(source, errors);
        int fallbacks = 0;
        for (Block block : Registries.BLOCK.values()) {
            if (block.isAir() || block.isFluid()) continue;
            if (!TABLES.containsKey(block)) {
                TABLES.put(block, selfDropFallback(block));
                fallbacks++;
            }
        }
        if (!errors.isEmpty()) {
            for (String error : errors) LOGGER.error(error);
            throw new IllegalStateException(errors.size() + " fehlerhafte Block-Loot-Tabellen");
        }
        LOGGER.info(TABLES.size() + " Block-Loot-Tabellen kompiliert");
        if (fallbacks > 0) LOGGER.info(fallbacks + " Blöcke verwenden den Self-Drop-Fallback");
    }

    static LootTable selfDropFallback(Block block) {
        Item item = de.skyengine.game.world.item.Items.forBlock(block);
        if (item == null) return (context, sink) -> {};
        return (context, sink) -> {
            if (context.hasExplosionDecay()
                    && context.random().nextFloat() > 1.0F / context.explosionRadius()) return;
            sink.accept(new ItemStack(item, 1), context.x(), context.y(), context.z());
        };
    }

    public static LootTable get(Block block) {
        return TABLES.get(block);
    }

    public static void generate(LootContext context, LootSink sink) {
        LootTable table = TABLES.get(context.state().getBlock());
        if (table != null) table.generate(context, sink);
    }

    /** Paketlokaler Einstieg für Parser-Regressionstests ohne Dateisystem. */
    static LootTable compileForTest(String json) {
        return compile(JsonParser.parseString(json).getAsJsonObject(), "<test>");
    }

    private static void loadSource(ContentSource source, List<String> errors) {
        File dir = source.blockLootTables();
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        java.util.Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            String path = file.getName().substring(0, file.getName().length() - 5);
            Identifier id = new Identifier(source.namespace(), path);
            Block block = Registries.BLOCK.get(id);
            if (block == null) {
                errors.add(file + ": Tabelle gehört zu keinem registrierten Block " + id);
                continue;
            }
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                TABLES.put(block, compile(root, file.toString()));
            } catch (Exception e) {
                errors.add(file + ": " + e.getMessage());
            }
        }
    }

    private static LootTable compile(JsonObject root, String source) {
        requireType(root, "type", "block", source);
        JsonArray poolsJson = requireArray(root, "pools", source);
        Pool[] pools = new Pool[poolsJson.size()];
        for (int i = 0; i < pools.length; i++) pools[i] = compilePool(poolsJson.get(i).getAsJsonObject(), source);
        String sequence = root.has("random_sequence") ? root.get("random_sequence").getAsString() : null;
        return (context, sink) -> {
            LootContext effective = sequence != null && context.world() != null
                    ? context.withRandom(context.world().lootRandom(sequence)) : context;
            for (Pool pool : pools) pool.generate(effective, sink);
        };
    }

    private static Pool compilePool(JsonObject json, String source) {
        Condition[] conditions = conditions(json, source);
        NumberProvider rolls = json.has("rolls") ? number(json.get("rolls"), source) : constant(1);
        JsonArray entriesJson = requireArray(json, "entries", source);
        Entry[] entries = new Entry[entriesJson.size()];
        for (int i = 0; i < entries.length; i++) entries[i] = entry(entriesJson.get(i).getAsJsonObject(), source);
        return new Pool(conditions, rolls, entries);
    }

    private static Entry entry(JsonObject json, String source) {
        String type = requireString(json, "type", source);
        Condition[] conditions = conditions(json, source);
        int weight = json.has("weight") ? json.get("weight").getAsInt() : 1;
        if (weight < 1) throw invalid(source, "weight muss positiv sein");
        if (type.equals("item")) {
            Item item = resolveItem(requireString(json, "name", source), source);
            Function[] functions = functions(json, source);
            return new ItemEntry(item, conditions, functions, weight);
        }
        if (type.equals("alternatives")) {
            JsonArray childrenJson = requireArray(json, "children", source);
            Entry[] children = new Entry[childrenJson.size()];
            for (int i = 0; i < children.length; i++) children[i] = entry(childrenJson.get(i).getAsJsonObject(), source);
            return new AlternativesEntry(children, conditions, weight);
        }
        throw invalid(source, "unbekannter Entry-Typ " + type);
    }

    private static Condition[] conditions(JsonObject owner, String source) {
        if (!owner.has("conditions")) return new Condition[0];
        JsonArray array = owner.getAsJsonArray("conditions");
        Condition[] result = new Condition[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = condition(array.get(i).getAsJsonObject(), source);
        return result;
    }

    private static Condition condition(JsonObject json, String source) {
        String type = requireString(json, "condition", source);
        return switch (type) {
            case "survives_explosion" -> context -> !context.hasExplosionDecay()
                    || context.random().nextFloat() <= 1.0F / context.explosionRadius();
            case "random_chance" -> {
                float chance = json.get("chance").getAsFloat();
                yield context -> context.random().nextFloat() < chance;
            }
            case "inverted" -> {
                Condition term = condition(json.getAsJsonObject("term"), source);
                yield context -> !term.test(context);
            }
            case "any_of" -> {
                JsonArray termsJson = requireArray(json, "terms", source);
                Condition[] terms = new Condition[termsJson.size()];
                for (int i = 0; i < terms.length; i++) terms[i] = condition(termsJson.get(i).getAsJsonObject(), source);
                yield context -> {
                    for (Condition term : terms) if (term.test(context)) return true;
                    return false;
                };
            }
            case "table_bonus" -> {
                Enchantment enchantment = resolveEnchantment(requireString(json, "enchantment", source), source);
                float[] chances = floats(requireArray(json, "chances", source));
                yield context -> {
                    int level = context.tool() == null ? 0 : context.tool().getEnchantmentLevel(enchantment);
                    return context.random().nextFloat() < chances[Math.min(level, chances.length - 1)];
                };
            }
            case "match_tool" -> matchTool(json.getAsJsonObject("predicate"), source);
            case "block_state_property" -> blockStateCondition(json, source);
            case "entity_properties" -> {
                JsonObject predicate = json.getAsJsonObject("predicate");
                if (!"this".equals(requireString(json, "entity", source))
                        || (predicate != null && !predicate.entrySet().isEmpty())) {
                    throw invalid(source, "nur das leere entity_properties-Prädikat für 'this' wird unterstützt");
                }
                yield context -> true;
            }
            case "location_check" -> locationCondition(json, source);
            default -> throw invalid(source, "unbekannte Condition " + type);
        };
    }

    private static Condition locationCondition(JsonObject json, String source) {
        int dx = json.has("offsetX") ? json.get("offsetX").getAsInt() : 0;
        int dy = json.has("offsetY") ? json.get("offsetY").getAsInt() : 0;
        int dz = json.has("offsetZ") ? json.get("offsetZ").getAsInt() : 0;
        JsonObject predicate = json.getAsJsonObject("predicate");
        JsonObject blockPredicate = predicate == null ? null : predicate.getAsJsonObject("block");
        if (blockPredicate == null) throw invalid(source, "location_check benötigt ein Block-Prädikat");
        Block block = resolveBlock(requireString(blockPredicate, "blocks", source), source);
        JsonObject state = blockPredicate.getAsJsonObject("state");
        List<StateValue> values = new ArrayList<>();
        if (state != null) for (Map.Entry<String, JsonElement> entry : state.entrySet()) {
            values.add(new StateValue(property(block, entry.getKey(), source), entry.getValue().getAsString()));
        }
        return context -> {
            if (context.world() == null) return false;
            BlockState found = de.skyengine.game.world.block.Blocks.getState(
                    context.world().getBlock(context.x() + dx, context.y() + dy, context.z() + dz));
            if (found.getBlock() != block) return false;
            for (StateValue value : values) {
                Object actual = found.getValues().get(value.property());
                if (actual == null || !stateString(actual).equals(value.value())) return false;
            }
            return true;
        };
    }

    private static Condition matchTool(JsonObject predicate, String source) {
        if (predicate == null) throw invalid(source, "match_tool ohne predicate");
        List<Item> items = new ArrayList<>();
        if (predicate.has("items")) {
            JsonElement element = predicate.get("items");
            if (element.isJsonArray()) for (JsonElement value : element.getAsJsonArray()) items.add(resolveItem(value.getAsString(), source));
            else items.add(resolveItem(element.getAsString(), source));
        }
        List<EnchantmentLevel> enchantments = new ArrayList<>();
        JsonObject predicates = predicate.getAsJsonObject("predicates");
        if (predicates != null && predicates.has("enchantments")) {
            for (JsonElement element : predicates.getAsJsonArray("enchantments")) {
                JsonObject value = element.getAsJsonObject();
                Enchantment enchantment = resolveEnchantment(requireString(value, "enchantments", source), source);
                JsonObject levels = value.getAsJsonObject("levels");
                int min = levels == null ? 1 : levels.has("min") ? levels.get("min").getAsInt() : 1;
                int max = levels != null && levels.has("max") ? levels.get("max").getAsInt() : Integer.MAX_VALUE;
                enchantments.add(new EnchantmentLevel(enchantment, min, max));
            }
        }
        ToolType toolType = null;
        ToolTier minTier = null;
        ToolTier maxTier = null;
        if (predicates != null && predicates.has("tool")) {
            JsonObject tool = predicates.getAsJsonObject("tool");
            toolType = ToolType.byName(requireString(tool, "type", source));
            minTier = tool.has("min_tier") ? ToolTier.byName(tool.get("min_tier").getAsString()) : null;
            maxTier = tool.has("max_tier") ? ToolTier.byName(tool.get("max_tier").getAsString()) : null;
            if (toolType == null || (tool.has("min_tier") && minTier == null) || (tool.has("max_tier") && maxTier == null)) {
                throw invalid(source, "ungültiges tool-Prädikat");
            }
        }
        ToolType finalToolType = toolType;
        ToolTier finalMinTier = minTier;
        ToolTier finalMaxTier = maxTier;
        return context -> {
            ItemStack stack = context.tool();
            if (stack == null || stack.isEmpty()) return false;
            if (!items.isEmpty() && !items.contains(stack.getItem())) return false;
            for (EnchantmentLevel test : enchantments) {
                int level = stack.getEnchantmentLevel(test.enchantment());
                if (level < test.min() || level > test.max()) return false;
            }
            if (finalToolType != null) {
                if (!(stack.getItem() instanceof ToolItem tool) || tool.getType() != finalToolType) return false;
                if (finalMinTier != null && tool.getTier().level() < finalMinTier.level()) return false;
                if (finalMaxTier != null && tool.getTier().level() > finalMaxTier.level()) return false;
            }
            return true;
        };
    }

    private static Condition blockStateCondition(JsonObject json, String source) {
        Block block = resolveBlock(requireString(json, "block", source), source);
        JsonObject properties = json.getAsJsonObject("properties");
        List<StateValue> values = new ArrayList<>();
        if (properties != null) for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            Property<?> property = property(block, entry.getKey(), source);
            values.add(new StateValue(property, entry.getValue().getAsString()));
        }
        return context -> {
            if (context.state().getBlock() != block) return false;
            for (StateValue value : values) {
                Object actual = context.state().getValues().get(value.property());
                if (actual == null || !stateString(actual).equals(value.value())) return false;
            }
            return true;
        };
    }

    private static Function[] functions(JsonObject owner, String source) {
        if (!owner.has("functions")) return new Function[0];
        JsonArray array = owner.getAsJsonArray("functions");
        Function[] result = new Function[array.size()];
        for (int i = 0; i < result.length; i++) result[i] = function(array.get(i).getAsJsonObject(), source);
        return result;
    }

    private static Function function(JsonObject json, String source) {
        String type = requireString(json, "function", source);
        Condition[] conditions = conditions(json, source);
        Function action = switch (type) {
            case "set_count" -> {
                NumberProvider count = number(json.get("count"), source);
                boolean add = json.has("add") && json.get("add").getAsBoolean();
                yield (stack, context) -> stack.setCount(add ? stack.getCount() + count.next(context) : count.next(context));
            }
            case "limit_count" -> {
                JsonObject limit = json.getAsJsonObject("limit");
                int min = limit != null && limit.has("min") ? limit.get("min").getAsInt() : 0;
                int max = limit != null && limit.has("max") ? limit.get("max").getAsInt() : Integer.MAX_VALUE;
                yield (stack, context) -> stack.setCount(Math.max(min, Math.min(max, stack.getCount())));
            }
            case "explosion_decay" -> (stack, context) -> {
                if (!context.hasExplosionDecay()) return;
                int kept = 0;
                float chance = 1.0F / context.explosionRadius();
                for (int i = 0; i < stack.getCount(); i++) if (context.random().nextFloat() <= chance) kept++;
                stack.setCount(kept);
            };
            case "apply_bonus" -> applyBonus(json, source);
            case "copy_components" -> {
                if (!"block_entity".equals(requireString(json, "source", source))) {
                    throw invalid(source, "copy_components unterstützt nur block_entity");
                }
                JsonArray include = requireArray(json, "include", source);
                for (JsonElement component : include) {
                    if (!"custom_name".equals(component.getAsString())) {
                        throw invalid(source, "copy_components-Komponente noch nicht unterstützt: " + component);
                    }
                }
                /* BlockEntities und ItemStacks besitzen noch keine benutzerdefinierten Namen.
                   Die validierte Funktion ist daher bis zur Component-Einführung wirkungsgleich leer. */
                yield (stack, context) -> {};
            }
            default -> throw invalid(source, "unbekannte Function " + type);
        };
        if (conditions.length == 0) return action;
        return (stack, context) -> {
            if (all(conditions, context)) action.apply(stack, context);
        };
    }

    private static Function applyBonus(JsonObject json, String source) {
        Enchantment enchantment = resolveEnchantment(requireString(json, "enchantment", source), source);
        String formula = requireString(json, "formula", source);
        if (formula.equals("ore_drops")) return (stack, context) -> {
            int level = context.tool() == null ? 0 : context.tool().getEnchantmentLevel(enchantment);
            if (level <= 0) return;
            int bonus = context.random().nextInt(level + 2) - 1;
            if (bonus < 0) bonus = 0;
            stack.setCount(stack.getCount() * (bonus + 1));
        };
        if (formula.equals("uniform_bonus_count")) {
            int multiplier = json.has("parameters") && json.getAsJsonObject("parameters").has("bonusMultiplier")
                    ? json.getAsJsonObject("parameters").get("bonusMultiplier").getAsInt() : 1;
            return (stack, context) -> {
                int level = context.tool() == null ? 0 : context.tool().getEnchantmentLevel(enchantment);
                stack.setCount(stack.getCount() + context.random().nextInt(multiplier * level + 1));
            };
        }
        throw invalid(source, "unbekannte Bonus-Formel " + formula);
    }

    private static NumberProvider number(JsonElement json, String source) {
        if (json == null) throw invalid(source, "Zahlenwert fehlt");
        if (json.isJsonPrimitive()) {
            int value = Math.round(json.getAsFloat());
            return constant(value);
        }
        JsonObject object = json.getAsJsonObject();
        requireType(object, "type", "uniform", source);
        float min = object.get("min").getAsFloat();
        float max = object.get("max").getAsFloat();
        if (max < min) throw invalid(source, "uniform.max liegt unter min");
        return context -> (int) Math.floor(min + context.random().nextDouble() * (max - min + 1.0));
    }

    private static NumberProvider constant(int value) { return context -> value; }

    private static Item resolveItem(String value, String source) {
        Identifier id = mapped(value);
        Item item = Registries.ITEM.get(id);
        if (item == null) throw invalid(source, "unbekanntes Item " + value + " (aufgelöst als " + id + ")");
        return item;
    }

    private static Block resolveBlock(String value, String source) {
        Identifier id = mapped(value);
        Block block = Registries.BLOCK.get(id);
        if (block == null) throw invalid(source, "unbekannter Block " + value + " (aufgelöst als " + id + ")");
        return block;
    }

    private static Enchantment resolveEnchantment(String value, String source) {
        Enchantment enchantment = Enchantments.get(Identifier.of(value));
        if (enchantment == null) throw invalid(source, "unbekannte Verzauberung " + value);
        return enchantment;
    }

    private static Identifier mapped(String value) {
        return Identifier.of(value);
    }

    private static Property<?> property(Block block, String name, String source) {
        for (Property<?> property : block.getDefaultState().getValues().keySet()) {
            if (property.getName().equals(name)) return property;
        }
        throw invalid(source, "Block " + block.getIdentifier() + " hat kein Property " + name);
    }

    private static String stateString(Object value) {
        return value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : value.toString();
    }

    private static boolean all(Condition[] conditions, LootContext context) {
        for (Condition condition : conditions) if (!condition.test(context)) return false;
        return true;
    }

    private static float[] floats(JsonArray array) {
        if (array.isEmpty()) throw new IllegalArgumentException("chances darf nicht leer sein");
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) values[i] = array.get(i).getAsFloat();
        return values;
    }

    private static JsonArray requireArray(JsonObject object, String name, String source) {
        if (!object.has(name) || !object.get(name).isJsonArray()) throw invalid(source, "Array fehlt: " + name);
        return object.getAsJsonArray(name);
    }

    private static String requireString(JsonObject object, String name, String source) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) throw invalid(source, "String fehlt: " + name);
        return object.get(name).getAsString();
    }

    private static void requireType(JsonObject object, String name, String expected, String source) {
        String actual = requireString(object, name, source);
        if (!expected.equals(actual)) throw invalid(source, "erwartet " + expected + " bei " + name + ", erhalten " + actual);
    }

    private static IllegalArgumentException invalid(String source, String detail) {
        return new IllegalArgumentException(source + ": " + detail);
    }

    private static final class Pool {
        private final Condition[] conditions;
        private final NumberProvider rolls;
        private final Entry[] entries;
        private final ThreadLocal<boolean[]> eligibility;

        private Pool(Condition[] conditions, NumberProvider rolls, Entry[] entries) {
            this.conditions = conditions;
            this.rolls = rolls;
            this.entries = entries;
            this.eligibility = ThreadLocal.withInitial(() -> new boolean[entries.length]);
        }

        void generate(LootContext context, LootSink sink) {
            if (!all(conditions, context)) return;
            int count = Math.max(0, rolls.next(context));
            for (int roll = 0; roll < count; roll++) {
                if (entries.length == 1) {
                    if (entries[0].canRun(context)) entries[0].emit(context, sink);
                    continue;
                }
                boolean[] eligible = eligibility.get();
                int total = 0;
                for (int i = 0; i < entries.length; i++) {
                    eligible[i] = entries[i].canRun(context);
                    if (eligible[i]) total += entries[i].weight();
                }
                if (total == 0) continue;
                int selected = context.random().nextInt(total);
                for (int i = 0; i < entries.length; i++) {
                    if (!eligible[i]) continue;
                    Entry entry = entries[i];
                    selected -= entry.weight();
                    if (selected < 0) { entry.emit(context, sink); break; }
                }
            }
        }
    }

    private interface Entry {
        boolean canRun(LootContext context);
        int weight();
        boolean emit(LootContext context, LootSink sink);
    }

    private record ItemEntry(Item item, Condition[] conditions, Function[] functions, int weight) implements Entry {
        public boolean canRun(LootContext context) { return all(conditions, context); }
        public boolean emit(LootContext context, LootSink sink) {
            ItemStack stack = new ItemStack(item, 1);
            for (Function function : functions) function.apply(stack, context);
            if (!stack.isEmpty()) sink.accept(stack, context.x(), context.y(), context.z());
            return true;
        }
    }

    private record AlternativesEntry(Entry[] children, Condition[] conditions, int weight) implements Entry {
        public boolean canRun(LootContext context) { return all(conditions, context); }
        public boolean emit(LootContext context, LootSink sink) {
            for (Entry child : children) if (child.canRun(context) && child.emit(context, sink)) return true;
            return false;
        }
    }

    @FunctionalInterface private interface Condition { boolean test(LootContext context); }
    @FunctionalInterface private interface Function { void apply(ItemStack stack, LootContext context); }
    @FunctionalInterface private interface NumberProvider { int next(LootContext context); }
    private record EnchantmentLevel(Enchantment enchantment, int min, int max) {}
    private record StateValue(Property<?> property, String value) {}

    private LootTables() {}
}
