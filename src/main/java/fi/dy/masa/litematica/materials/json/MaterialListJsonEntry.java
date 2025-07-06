package fi.dy.masa.litematica.materials.json;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import javax.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.NetworkRecipeId;
import net.minecraft.recipe.RecipeDisplayEntry;
import net.minecraft.recipe.book.RecipeBookCategory;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.context.ContextParameterMap;
import net.minecraft.util.math.MathHelper;

import fi.dy.masa.malilib.mixin.recipe.IMixinIngredient;
import fi.dy.masa.malilib.util.game.RecipeBookUtils;
import fi.dy.masa.malilib.util.log.AnsiLogger;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.data.CachedTagManager;

@ApiStatus.Experimental
public class MaterialListJsonEntry
{
    private static final AnsiLogger LOGGER = new AnsiLogger(MaterialListJsonEntry.class, true, true);
    private final List<MaterialListJsonBase> requirements;
    private final RegistryEntry<Item> inputItem;
    private final int total;
    private final Type type;
    private boolean hasOutput = false;
    private NetworkRecipeId primaryId;
    private HashMap<NetworkRecipeId, List<Ingredient>> recipeRequirements;
    private HashMap<NetworkRecipeId, RecipeBookCategory> recipeCategory;
    private HashMap<NetworkRecipeId, RecipeBookUtils.Type> recipeTypes;

    private MaterialListJsonEntry(RegistryEntry<Item> inputItem, int total, Type type)
    {
        this.requirements = new ArrayList<>();
        this.inputItem = inputItem;
        this.total = total;
        this.type = type;
    }

    public static @Nullable MaterialListJsonEntry build(RegistryEntry<Item> input, final int total, List<RecipeBookUtils.Type> types, @Nullable RegistryEntry<Item> prevItem)
    {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (input == null || mc.world == null) return null;
        if (types.isEmpty())
        {
            // No types to match (Remaining logic)
            return new MaterialListJsonEntry(input, total, Type.EMPTY);
        }

        ItemStack shadow = new ItemStack(input);
        List<Pair<NetworkRecipeId, RecipeDisplayEntry>> lookup = RecipeBookUtils.getDisplayEntryFromRecipeBook(shadow, types);
        ContextParameterMap map = RecipeBookUtils.getMap(mc);

        if (lookup.isEmpty())
        {
            // No recipes found.
            return new MaterialListJsonEntry(input, total, Type.LAST);
        }

        final int lookupCount = lookup.size();
        final Type outType = lookupCount > 1 ? Type.MULTI : Type.ONE;

        Litematica.LOGGER.warn("MaterialListJsonEntry#build(): Found [{}] recipe(s) for item [{}]", lookupCount, input.getIdAsString());

        MaterialListJsonEntry result = new MaterialListJsonEntry(input, total, outType);
        result.recipeRequirements = new HashMap<>();
        result.recipeCategory = new HashMap<>();
        result.recipeTypes = new HashMap<>();
        result.hasOutput = true;

        // Only report the first entry (It's just redundant work, but we flagged it as MULTI)
        Pair<NetworkRecipeId, RecipeDisplayEntry> pair = lookup.getFirst();
        NetworkRecipeId id = pair.getLeft();
        RecipeDisplayEntry entry = pair.getRight();
        List<ItemStack> resultStacks = entry.getStacks(map);
        ItemStack resultStack = resultStacks.getFirst();
        int resultCount = resultStack.getCount();
        RecipeBookCategory category = entry.category();
        RecipeBookUtils.Type type = RecipeBookUtils.Type.fromRecipeDisplay(entry.display());

        // Stacks was already verified
        if (entry.craftingRequirements().isPresent())
        {
            List<Ingredient> ingredients = entry.craftingRequirements().get();

            // Override for repetitive recipe types; such as Re-Coloring of beds.
            if (lookupCount > 1 && overrideShouldSkipRecipe(input, ingredients))
            {
                pair = lookup.get(1);
                id = pair.getLeft();
                entry = pair.getRight();

                if (entry.craftingRequirements().isPresent())
                {
                    Litematica.LOGGER.warn("MaterialListJsonEntry#build(): skipping recipe for [{}]", resultStack.toString());
                    resultStacks = entry.getStacks(map);
                    resultStack = resultStacks.getFirst();
                    resultCount = resultStack.getCount();
                    category = entry.category();
                    type = RecipeBookUtils.Type.fromRecipeDisplay(entry.display());
                    ingredients = entry.craftingRequirements().get();
                }
                else
                {
                    pair = lookup.getFirst();
                    id = pair.getLeft();
                    entry = pair.getRight();
                }
            }

            result.recipeRequirements.put(id, ingredients);
            result.recipeCategory.put(id, category);
            result.recipeTypes.put(id, type);
            result.primaryId = id;

            HashMap<RegistryEntry<Item>, Integer> ded = new HashMap<>();

            for (Ingredient ing : ingredients)
            {
                SlotDisplay display = ing.toDisplay();
                ItemStack displayStack = display.getFirst(map);
                RegistryEntry<Item> itemEntry = displayStack.getRegistryEntry();
                RegistryEntryList<Item> ingEntries = ((IMixinIngredient) (Object) ing).malilib_getEntries();

                if (ingEntries.size() > 1)
                {
                    itemEntry = overridePrimaryMaterial(ingEntries.get(0));
                    display = new SlotDisplay.ItemSlotDisplay(itemEntry);
                    displayStack = itemEntry.value().getDefaultStack();
                    Litematica.LOGGER.warn("MaterialListJsonEntry#build(): ingredient [{}] reduced to a single item from [{}] entries", itemEntry.getIdAsString(), ingEntries.size());
                }

                if (prevItem != null && prevItem == itemEntry)
                {
                    // Stop loops.
                    Litematica.LOGGER.warn("MaterialListJsonEntry#build(): ingredient matches previous item [{}] ... Skipping", prevItem.getIdAsString());
                    continue;
                }

                LOGGER.warn("build(): ResultStack: [{}] // Result Count: [{}]", resultStack.toString(), resultCount);
                int adjustedTotal = total;

                if (resultCount > 1)
                {
                    final float adjusted = ((float) total / resultCount);
                    final int floor = MathHelper.floor(adjusted);
                    final int remainderCount = MathHelper.floor(resultCount * (adjusted - floor));
                    adjustedTotal = Math.max(floor + (remainderCount > 0 ? 1 : 0), (remainderCount > 0 ? 1 : 0));

                    LOGGER.warn("build(): adjusted: [{}], floor: [{}] // remainderCount: [{}] // AdjustedTotal: [{}]", adjusted, floor, remainderCount, adjustedTotal);
                }

                if (ded.containsKey(itemEntry))
                {
                    final int count = ded.get(itemEntry) + adjustedTotal;
                    ded.put(itemEntry, count);
                }
                else
                {
                    ded.put(itemEntry, adjustedTotal);
                }
            }

            Litematica.LOGGER.warn("MaterialListJsonEntry#build(): Found [{}] sub-materials(s) for item [{}]", ded.size(), input.getIdAsString());

            ded.forEach(
                    (key, count) ->
                            result.requirements.add(new MaterialListJsonBase(key, count, input))
            );
        }

        return result;
    }

    // Overrides for particular cases, such as redying of beds instead of choosing the Wool recipe.
    private static boolean overrideShouldSkipRecipe(RegistryEntry<Item> input, List<Ingredient> ingredients)
    {
        for (Ingredient ing : ingredients)
        {
            if (input.isIn(ItemTags.BEDS))
            {
                if (ing.test(Items.WHITE_BED.getDefaultStack()) ||
                    ing.test(Items.BLACK_BED.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.WOOL))
            {
                if (ing.test(Items.WHITE_WOOL.getDefaultStack()) ||
                    ing.test(Items.BLACK_WOOL.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.WOOL_CARPETS))
            {
                if (ing.test(Items.WHITE_CARPET.getDefaultStack()) ||
                    ing.test(Items.BLACK_CARPET.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.CANDLES))
            {
                if (ing.test(Items.WHITE_CANDLE.getDefaultStack()) ||
                    ing.test(Items.BLACK_CANDLE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.SHULKER_BOXES))
            {
                if (ing.test(Items.WHITE_SHULKER_BOX.getDefaultStack()) ||
                    ing.test(Items.BLACK_SHULKER_BOX.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.BANNERS))
            {
                if (ing.test(Items.WHITE_BANNER.getDefaultStack()) ||
                    ing.test(Items.BLACK_BANNER.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (input.isIn(ItemTags.TERRACOTTA))
            {
                if (ing.test(Items.WHITE_TERRACOTTA.getDefaultStack()) ||
                    ing.test(Items.BLACK_TERRACOTTA.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_STAINED_GLASS.getDefaultStack()) ||
                    ing.test(Items.BLACK_STAINED_GLASS.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_PANE_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_STAINED_GLASS_PANE.getDefaultStack()) ||
                    ing.test(Items.BLACK_STAINED_GLASS_PANE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_CONCRETE.getDefaultStack()) ||
                    ing.test(Items.BLACK_CONCRETE.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_POWDER_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_CONCRETE_POWDER.getDefaultStack()) ||
                    ing.test(Items.BLACK_CONCRETE_POWDER.getDefaultStack()))
                {
                    return true;
                }
            }
            else if (CachedTagManager.matchItemTag(CachedTagManager.GLAZED_TERRACOTTA_ITEMS_KEY, input))
            {
                if (ing.test(Items.WHITE_GLAZED_TERRACOTTA.getDefaultStack()) ||
                    ing.test(Items.BLACK_GLAZED_TERRACOTTA.getDefaultStack()))
                {
                    return true;
                }
            }
        }

        return false;
    }

    // Overrides for re-dying recipe's
    private static RegistryEntry<Item> overridePrimaryMaterial(RegistryEntry<Item> firstItem)
    {
        if (firstItem.isIn(ItemTags.WOOL))
        {
            return Registries.ITEM.getEntry(Items.WHITE_WOOL);
        }
        else if (firstItem.isIn(ItemTags.WOOL_CARPETS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CARPET);
        }
        else if (firstItem.isIn(ItemTags.BEDS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_BED);
        }
        else if (firstItem.isIn(ItemTags.CANDLES))
        {
            return Registries.ITEM.getEntry(Items.CANDLE);
        }
        else if (firstItem.isIn(ItemTags.SHULKER_BOXES))
        {
            return Registries.ITEM.getEntry(Items.SHULKER_BOX);
        }
        else if (firstItem.isIn(ItemTags.BANNERS))
        {
            return Registries.ITEM.getEntry(Items.WHITE_BANNER);
        }
        else if (firstItem.isIn(ItemTags.TERRACOTTA))
        {
            return Registries.ITEM.getEntry(Items.TERRACOTTA);
        }
        else if (firstItem.isIn(ItemTags.BUNDLES))
        {
            return Registries.ITEM.getEntry(Items.BUNDLE);
        }
        else if (firstItem.isIn(ItemTags.HARNESSES))
        {
            return Registries.ITEM.getEntry(Items.WHITE_HARNESS);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.GLASS);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLASS_PANE_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.GLASS_PANE);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_POWDER_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CONCRETE_POWDER);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.CONCRETE_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_CONCRETE);
        }
        else if (CachedTagManager.matchItemTag(CachedTagManager.GLAZED_TERRACOTTA_ITEMS_KEY, firstItem))
        {
            return Registries.ITEM.getEntry(Items.WHITE_GLAZED_TERRACOTTA);
        }

        return firstItem;
    }

    public Type getType() { return this.type; }

    public RegistryEntry<Item> getInputItem() { return this.inputItem; }

    public List<MaterialListJsonBase> getRequirements() { return this.requirements; }

    public NetworkRecipeId getPrimaryId() { return this.primaryId; }

    public HashMap<NetworkRecipeId, List<Ingredient>> getRecipeRequirements()
    {
        return this.recipeRequirements;
    }

    public HashMap<NetworkRecipeId, RecipeBookCategory> getRecipeCategory()
    {
        return this.recipeCategory;
    }

    public HashMap<NetworkRecipeId, RecipeBookUtils.Type> getRecipeTypes()
    {
        return this.recipeTypes;
    }

    public boolean hasOutput()
    {
        return this.hasOutput;
    }

    public int getTotal()
    {
        return this.total;
    }

    public enum Type
    {
        LAST,
        EMPTY,
        ONE,
        MULTI
    }

    public JsonElement toJson(RegistryOps<?> ops)
    {
        JsonObject obj = new JsonObject();

        obj.add("Item", new JsonPrimitive(this.getInputItem().getIdAsString()));
        obj.add("Count", new JsonPrimitive(this.getTotal()));
        obj.add("Type", new JsonPrimitive(this.type.name()));

        if (this.hasOutput())
        {
            obj.add("PrimaryId", new JsonPrimitive(this.getPrimaryId().index()));
            obj.add("Recipes", this.lookupResultsToJson(ops));
        }

        return obj;
    }

    private JsonArray lookupResultsToJson(RegistryOps<?> ops)
    {
        JsonArray arr = new JsonArray();

        for (NetworkRecipeId id : this.recipeRequirements.keySet())
        {
            List<Ingredient> requires = this.recipeRequirements.get(id);
            RecipeBookCategory category = this.recipeCategory.get(id);
            RecipeBookUtils.Type type = this.recipeTypes.get(id);
            JsonObject obj = new JsonObject();

            obj.add("NetworkId", new JsonPrimitive(id.index()));
            obj.add("Category", new JsonPrimitive(RecipeBookUtils.getRecipeCategoryId(category)));
            obj.add("Type", new JsonPrimitive(type.name()));

            JsonArray itemArr = new JsonArray();
            for (Ingredient ing : requires)
            {
                itemArr.add((JsonElement) Ingredient.CODEC.encodeStart(ops, ing).getPartialOrThrow());
            }
            obj.add("Ingredients", itemArr);

            JsonArray outputArr = new JsonArray();
            for (MaterialListJsonBase jsonEntry : this.requirements)
            {
                outputArr.add(jsonEntry.toJson(ops));
            }
            obj.add("Requirements", outputArr);

            arr.add(obj);
        }

        return arr;
    }
}
