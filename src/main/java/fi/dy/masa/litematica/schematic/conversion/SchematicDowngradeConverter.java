package fi.dy.masa.litematica.schematic.conversion;

import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.entity.EquipmentDropChances;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.*;
import net.minecraft.recipe.Recipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.Uuids;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.nbt.NbtBlockUtils;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import fi.dy.masa.litematica.Litematica;

public class SchematicDowngradeConverter
{
//    private static final AnsiLogger LOGGER = new AnsiLogger(SchematicDowngradeConverter.class, true, true);

    public static NbtCompound downgradeEntity_to_1_20_4(NbtCompound oldEntity, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound newEntity = new NbtCompound();

        if (!oldEntity.contains("id"))
        {
            return oldEntity;
        }
        for (String key : oldEntity.getKeys())
        {
            switch (key)
            {
                case "x" -> newEntity.putInt("x", oldEntity.getInt("x", 0));
                case "y" -> newEntity.putInt("y", oldEntity.getInt("y", 0));
                case "z" -> newEntity.putInt("z", oldEntity.getInt("z", 0));
                case "id" -> newEntity.putString("id", oldEntity.getString("id", ""));
                case "attributes" -> newEntity.put("Attributes", processAttributes(oldEntity.get(key), minecraftDataVersion, registryManager));
                case "flower_pos" -> newEntity.put("FlowerPos", processFlowerPos(oldEntity, key, minecraftDataVersion, registryManager));
                case "hive_pos" -> newEntity.put("HivePos", processFlowerPos(oldEntity, key, minecraftDataVersion, registryManager));
                case "ArmorItems" -> newEntity.put("ArmorItems", processEntityItems(oldEntity.getListOrEmpty(key), minecraftDataVersion, registryManager, 4));
                case "HandItems" -> newEntity.put("HandItems", processEntityItems(oldEntity.getListOrEmpty(key), minecraftDataVersion, registryManager, 2));
                case "Item" -> newEntity.put("Item", processEntityItem(oldEntity.get(key), minecraftDataVersion, registryManager));
                case "Inventory" -> newEntity.put("Inventory", processEntityItems(oldEntity.getListOrEmpty(key), minecraftDataVersion, registryManager, 1));
                // 1.21.5+ tags
                case "equipment" -> newEntity.copyFrom(processEntityEquipment(oldEntity.get(key), minecraftDataVersion, registryManager));
                case "drop_chances" -> newEntity.copyFrom(processEntityDropChances(oldEntity.get(key)));
                case "fall_distance" -> newEntity.putFloat("FallDistance", oldEntity.getFloat(key, 0f));
                // NbtUtils.readBlockPosFromArrayTag() // get(key, BlockPos.CODEC).orElse(null)
                case "anchor_pos" -> processBlockPosTag(NbtUtils.readBlockPosFromIntArray(oldEntity, key), "A", newEntity);
                case "block_pos" -> processBlockPosTag(NbtUtils.readBlockPosFromIntArray(oldEntity, key), "Tile", newEntity);
                case "bound_pos" -> processBlockPosTag(NbtUtils.readBlockPosFromIntArray(oldEntity, key), "Bound", newEntity);
                case "home_pos" -> processBlockPosTag(NbtUtils.readBlockPosFromIntArray(oldEntity, key), "HomePos", newEntity);
                case "sleeping_pos" -> processBlockPosTag(NbtUtils.readBlockPosFromIntArray(oldEntity, key), "Sleeping", newEntity);
                case "has_egg" -> newEntity.putBoolean("HasEgg", oldEntity.getBoolean(key, false));
                case "life_ticks" -> newEntity.putInt("LifeTicks", oldEntity.getInt(key, 0));
                case "size" -> newEntity.putInt("Size", oldEntity.getInt(key, 0));
                default -> newEntity.put(key, oldEntity.get(key));
            }
        }

        return newEntity;
    }

    private static void processBlockPosTag(@Nullable BlockPos oldPos, String prefix, NbtCompound newTags)
    {
        if (oldPos != null)
        {
            newTags.putInt(prefix+"X", oldPos.getX());
            newTags.putInt(prefix+"Y", oldPos.getY());
            newTags.putInt(prefix+"Z", oldPos.getZ());
        }
    }

    private static NbtCompound processEntityDropChances(NbtElement nbtElement)
    {
        NbtCompound oldTags = (NbtCompound) nbtElement;
        NbtCompound newTags = new NbtCompound();
        NbtList handDrops = new NbtList();
        NbtList armorDrops = new NbtList();

        for (int i = 0; i < 2; i++)
        {
            handDrops.add(NbtFloat.of(EquipmentDropChances.DEFAULT_CHANCE));
        }

        for (int i = 0; i < 4; i++)
        {
            armorDrops.add(NbtFloat.of(EquipmentDropChances.DEFAULT_CHANCE));
        }

        for (String key : oldTags.getKeys())
        {
            switch (key)
            {
                case "mainhand" -> handDrops.set(0, oldTags.get(key));
                case "offhand" -> handDrops.set(1, oldTags.get(key));
                case "feet" -> armorDrops.set(0, oldTags.get(key));
                case "legs" -> armorDrops.set(1, oldTags.get(key));
                case "chest" -> armorDrops.set(2, oldTags.get(key));
                case "head" -> armorDrops.set(3, oldTags.get(key));
                // Not used
                //case "body" -> newTags.put("body_armor_drop_chance", oldTags.get(key));
                //case "saddle" -> newTags.put("SaddleItem", oldTags.get(key));
                default -> {}
            }
        }

        newTags.put("HandDropChances", handDrops);
        newTags.put("ArmorDropChances", armorDrops);

        return newTags;
    }

    private static NbtCompound processEntityEquipment(NbtElement equipmentEntries, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound oldTags = (NbtCompound) equipmentEntries;
        NbtCompound newTags = new NbtCompound();
        NbtList newHandItems = new NbtList();
        NbtList newArmorItems = new NbtList();

        for (int i = 0; i < 2; i++)
        {
            newHandItems.add(new NbtCompound());
        }

        for (int i = 0; i < 4; i++)
        {
            newArmorItems.add(new NbtCompound());
        }

        for (String key : oldTags.getKeys())
        {
            switch (key)
            {
                case "mainhand" -> newHandItems.set(0, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "offhand" -> newHandItems.set(1, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "feet" -> newArmorItems.set(0, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "legs" -> newArmorItems.set(1, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "chest" -> newArmorItems.set(2, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "head" -> newArmorItems.set(3, processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                case "body" ->
                {
                    // Why is this duplicated in 1.20.4?  the world may never know...
                    NbtElement ele = processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager);
                    newArmorItems.set(2, ele);
                    newTags.put("ArmorItem", ele);
                }
                case "saddle" -> newTags.put("SaddleItem", processEntityItem(oldTags.get(key), minecraftDataVersion, registryManager));
                default -> {}
            }
        }

        newTags.put("HandItems", newHandItems);
        newTags.put("ArmorItems", newArmorItems);

        return newTags;
    }

    private static NbtElement processEntityItem(NbtElement itemEntry, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound oldItem = (NbtCompound) itemEntry;
        NbtCompound newItem = new NbtCompound();

        if (!oldItem.contains("id"))
        {
            return itemEntry;
        }
        String idName = oldItem.getString("id", "");
        newItem.putString("id", idName);
        if (oldItem.contains("count"))
        {
            newItem.putByte("Count", (byte) oldItem.getInt("count", 1));
        }
        if (oldItem.contains("components"))
        {
            newItem.put("tag", processComponentsTag(oldItem.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
        }
        else
        {
            if (needsDamageTag(idName))
            {
                NbtCompound newTag = new NbtCompound();
                newTag.putInt("Damage", 0);
                newItem.put("tag", newTag);
            }
        }

        return newItem;
    }

    private static NbtList processEntityItems(NbtList oldItems, int minecraftDataVersion, DynamicRegistryManager registryManager, int expectedSize)
    {
        NbtList newItems = new NbtList();

        for (int i = 0; i < oldItems.size(); i++)
        {
            NbtCompound itemEntry = oldItems.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            if (itemEntry.contains("id"))
            {
                String idName = itemEntry.getString("id", "");
                newEntry.putString("id", idName);

                if (itemEntry.contains("count"))
                {
                    newEntry.putByte("Count", (byte) itemEntry.getInt("count", 1));
                }
                else
                {
                    newEntry.putByte("Count", (byte) 1);
                }
                if (itemEntry.contains("components"))
                {
                    newEntry.put("tag", processComponentsTag(itemEntry.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
                }
                else
                {
                    if (needsDamageTag(idName))
                    {
                        NbtCompound newTag = new NbtCompound();
                        newTag.putInt("Damage", 0);
                        newEntry.put("tag", newTag);
                    }
                }
            }

            newItems.add(newEntry);
        }

        if (newItems.size() < expectedSize)
        {
            int addTotal = expectedSize - newItems.size();

            for (int i = 0; i < addTotal; i++)
            {
                newItems.add(i, new NbtCompound());
            }
        }

        return newItems;
    }

    private static NbtElement processAttributes(NbtElement attrib, int minecraftDataVersion, DynamicRegistryManager registryManager)
    {
        NbtList oldAttr = (NbtList) attrib;
        NbtList newAttr = new NbtList();

        for (int i = 0; i < oldAttr.size(); i++)
        {
            NbtCompound attrEntry = oldAttr.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            newEntry.putString("Name", attributeRename(attrEntry.getString("id", "")));
            newEntry.putDouble("Base", attrEntry.getDouble("base", 0D));

            NbtList listEntry = attrEntry.getListOrEmpty("modifiers");
            NbtList newMods = new NbtList();

            for (int y = 0; y < listEntry.size(); y++)
            {
                NbtCompound modEntry = listEntry.getCompoundOrEmpty(y);
                NbtCompound newMod = new NbtCompound();

                newMod.putDouble("Amount", modEntry.getDouble("amount", 0D));
                newMod.putString("Name", modifierIdToName(modEntry.getString("id", "")));
                newMod.putInt("Operation", modifierOperationToInt(modEntry.getString("operation", "")));
                //newMod.putUuid("UUID", modEntry.contains("UUID") ? modEntry.getUuid("UUID") : UUID.randomUUID());
                newMod.put("UUID", Uuids.CODEC, modEntry.get("UUID", Uuids.CODEC, registryManager.getOps(NbtOps.INSTANCE)).orElse(UUID.randomUUID()));
                newMods.add(newMod);
            }
            if (!newMods.isEmpty())
            {
                newEntry.put("Modifiers", newMods);
            }

            newAttr.add(newEntry);
        }

        return newAttr;
    }

    private static String attributeRename(String idIn)
    {
        switch (idIn)
        {
            case "minecraft:armor" ->
            {
                return "minecraft:generic.armor";
            }
            case "minecraft:armor_toughness" ->
            {
                return "minecraft:generic.armor_toughness";
            }
            case "minecraft:attack_damage" ->
            {
                return "minecraft:generic.attack_damage";
            }
            case "minecraft:attack_knockback" ->
            {
                return "minecraft:generic.attack_knockback";
            }
            case "minecraft:attack_speed" ->
            {
                return "minecraft:generic.attack_speed";
            }
            case "minecraft:flying_speed" ->
            {
                return "minecraft:generic.flying_speed";
            }
            case "minecraft:follow_range" ->
            {
                return "minecraft:generic.follow_range";
            }
            case "minecraft:jump_strength" ->
            {
                return "minecraft:horse.jump_strength";
                // return "minecraft:generic.jump_strength"; --> (1.20.6 / 1.21 only)
            }
            case "minecraft:knockback_resistance" ->
            {
                return "minecraft:generic.knockback_resistance";
            }
            case "minecraft:luck" ->
            {
                return "minecraft:generic.luck";
            }
            case "minecraft:max_absorption" ->
            {
                return "minecraft:generic.max_absorption";
            }
            case "minecraft:max_health" ->
            {
                return "minecraft:generic.max_health";
            }
            case "minecraft:movement_speed" ->
            {
                return "minecraft:generic.movement_speed";
            }
            case "minecraft:spawn_reinforcements" ->
            {
                return "minecraft:zombie.spawn_reinforcements";
            }

            // tempt_range --> No match
            // These don't exist in 1.20.4 (1.20.6 / 1.21 only)
            case "minecraft:block_break_speed" ->
            {
                return "minecraft:player.block_break_speed";
            }
            case "minecraft:block_interaction_range" ->
            {
                return "minecraft:player.block_interaction_range";
            }
            case "minecraft:burning_time" ->
            {
                return "minecraft:generic.burning_time";
            }
            case "minecraft:explosion_knockback_resistance" ->
            {
                return "minecraft:generic.explosion_knockback_resistance";
            }
            case "minecraft:entity_interaction_range" ->
            {
                return "minecraft:player.entity_interaction_range";
            }
            case "minecraft:fall_damage_multiplier" ->
            {
                return "minecraft:generic.fall_damage_multiplier";
            }
            case "minecraft:gravity" ->
            {
                return "minecraft:generic.gravity";
            }
            case "minecraft:mining_efficiency" ->
            {
                return "minecraft:player.mining_efficiency";
            }
            case "minecraft:movement_efficiency" ->
            {
                return "minecraft:generic.movement_efficiency";
            }
            case "minecraft:oxygen_bonus" ->
            {
                return "minecraft:generic.oxygen_bonus";
            }
            case "minecraft:safe_fall_distance" ->
            {
                return "minecraft:generic.safe_fall_distance";
            }
            case "minecraft:scale" ->
            {
                return "minecraft:generic.scale";
            }
            case "minecraft:sneaking_speed" ->
            {
                return "minecraft:player.sneaking_speed";
            }
            case "minecraft:step_height" ->
            {
                return "minecraft:generic.step_height";
            }
            case "minecraft:submerged_mining_speed" ->
            {
                return "minecraft:player.submerged_mining_speed";
            }
            case "minecraft:sweeping_damage_ratio" ->
            {
                return "minecraft:player.sweeping_damage_ratio";
            }
            case "minecraft:water_movement_efficiency" ->
            {
                return "minecraft:generic.water_movement_efficiency";
            }
        }

        return idIn;
    }

    private static String modifierIdToName(String idIn)
    {
        if (idIn.equals("minecraft:random_spawn_bonus"))
        {
            return "Random spawn bonus";
        }

        return "";
    }

    private static int modifierOperationToInt(String op)
    {
        switch (op)
        {
            case "add_value" ->
            {
                return 0;
            }
            case "add_multiplied_base" ->
            {
                return 1;
            }
            case "add_multiplied_total" ->
            {
                return 2;
            }
        }

        return 0;
    }

    public static NbtCompound downgradeBlockEntity_to_1_20_4(NbtCompound oldTE, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound newTE = new NbtCompound();

        if (!oldTE.contains("id"))
        {
            oldTE.copyFrom(SchematicConversionMaps.checkForIdTag(oldTE));
        }
        for (String key : oldTE.getKeys())
        {
            switch (key)
            {
                case "x" -> newTE.putInt("x", oldTE.getInt("x", 0));
                case "y" -> newTE.putInt("y", oldTE.getInt("y", 0));
                case "z" -> newTE.putInt("z", oldTE.getInt("z", 0));
                case "id" -> newTE.putString("id", oldTE.getString("id", ""));
                case "Items" -> newTE.put("Items", processItemsTag(oldTE.getListOrEmpty("Items"), minecraftDataVersion, registryManager));
                case "patterns" -> newTE.put("Patterns", processBannerPatterns(oldTE.get(key)));
                case "profile" -> newTE.put("SkullOwner", processSkullProfile(oldTE.get(key), newTE, minecraftDataVersion, registryManager));
                case "flower_pos" -> newTE.put("FlowerPos", processFlowerPos(oldTE, key, minecraftDataVersion, registryManager));
                case "bees" -> newTE.put("Bees", processBeesTag(oldTE.get(key), minecraftDataVersion, registryManager));
                case "item" -> newTE.put("item", processDecoratedPot(oldTE.get(key), minecraftDataVersion, registryManager));
                case "last_interacted_slot" -> newTE.put("last_interacted_slot", oldTE.get(key));
                case "ticks_since_song_started" ->
                {
                    newTE.putLong("RecordStartTick", 0L);
                    newTE.putLong("TickCount", oldTE.getLong(key, 0L));
                    newTE.putByte("IsPlaying", (byte) 0);
                }
                case "RecordItem" -> newTE.put("RecordItem", processRecordItem(oldTE.get(key), minecraftDataVersion, registryManager));
                case "Book" -> newTE.put("Book", processBookTag(oldTE.get(key), minecraftDataVersion, registryManager));
                // 1.21.5+
                //case "RecipesUsed" -> newTE.put("RecipesUsed", processRecipesUsedTag(oldTE));
                case "CustomName" -> newTE.putString("CustomName", processCustomNameTag(oldTE, key, registryManager));
                case "custom_name" -> newTE.putString("CustomName", processCustomNameTag(oldTE, key, registryManager));
                default -> newTE.put(key, oldTE.get(key));
            }
        }

        return newTE;
    }

    // 1.21.5+ Only ?  Might not even be needed
    private static NbtCompound processRecipesUsedTag(NbtElement nbtIn)
    {
        NbtCompound oldNbt = (NbtCompound) nbtIn;
        NbtCompound newNbt = new NbtCompound();
        Codec<Map<RegistryKey<Recipe<?>>, Integer>> CODEC = Codec.unboundedMap(Recipe.KEY_CODEC, Codec.INT);
        Reference2IntOpenHashMap<RegistryKey<Recipe<?>>> recipesUsed = new Reference2IntOpenHashMap<>();

        // todo -- make sure this even needed
        recipesUsed.putAll((Map<? extends RegistryKey<Recipe<?>>, ? extends Integer>) oldNbt.get("RecipesUsed", CODEC).orElse(Map.of()));
        recipesUsed.forEach((id, count) ->
        {
            newNbt.putInt(id.getValue().toString(), count);
        });

        return newNbt;
    }

    private static NbtList processItemsTag(NbtList oldItems, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtList newItems = new NbtList();

        for (int i = 0; i < oldItems.size(); i++)
        {
            NbtCompound itemEntry = oldItems.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            if (!itemEntry.contains("id"))
            {
                continue;
            }
            String idName = itemEntry.getString("id", "");

            newEntry.putString("id", idName);
            if (itemEntry.contains("count"))
            {
                newEntry.putByte("Count", (byte) itemEntry.getInt("count", 1));
            }
            if (itemEntry.contains("Slot"))
            {
                newEntry.putByte("Slot", itemEntry.getByte("Slot", (byte) 1));
            }
            if (itemEntry.contains("components"))
            {
                newEntry.put("tag", processComponentsTag(itemEntry.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
            }
            else
            {
                if (needsDamageTag(idName))
                {
                    NbtCompound newTag = new NbtCompound();
                    newTag.putInt("Damage", 0);
                    newEntry.put("tag", newTag);
                }
            }

            newItems.add(newEntry);
        }

        return newItems;
    }

    private static NbtList processItemsTag_Nested(NbtList oldItems, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtList newItems = new NbtList();

        for (int i = 0; i < oldItems.size(); i++)
        {
            NbtCompound itemEntry = oldItems.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            int slotNum = itemEntry.getInt("slot", 0);
            NbtCompound itemSlot = itemEntry.getCompoundOrEmpty("item");

            if (!itemSlot.contains("id"))
            {
                continue;
            }
            String idName = itemSlot.getString("id", "");

            newEntry.putString("id", idName);
            if (itemSlot.contains("count"))
            {
                newEntry.putByte("Count", (byte) itemSlot.getInt("count", 1));
            }
            newEntry.putByte("Slot", (byte) slotNum);

            if (itemSlot.contains("components"))
            {
                newEntry.put("tag", processComponentsTag(itemSlot.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
            }
            else
            {
                if (needsDamageTag(idName))
                {
                    NbtCompound newTag = new NbtCompound();
                    newTag.putInt("Damage", 0);
                    newEntry.put("tag", newTag);
                }
            }

            newItems.add(newEntry);
        }

        return newItems;
    }

    private static NbtCompound processDecoratedPot_Nested(NbtList oldItems, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound itemEntry = oldItems.getCompoundOrEmpty(0);
        NbtCompound newEntry = new NbtCompound();

        int slotNum = itemEntry.getInt("slot", 1);
        NbtCompound itemSlot = itemEntry.getCompoundOrEmpty("item");

        if (!itemSlot.contains("id"))
        {
            return itemEntry;
        }
        String idName = itemSlot.getString("id", "");
        newEntry.putString("id", idName);
        newEntry.putByte("Count", (byte) (itemSlot.contains("count") ? itemSlot.getInt("count") : 1));

        if (itemSlot.contains("components"))
        {
            newEntry.put("tag", processComponentsTag(itemSlot.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
        }
        else
        {
            if (needsDamageTag(idName))
            {
                NbtCompound newTag = new NbtCompound();
                newTag.putInt("Damage", 0);
                newEntry.put("tag", newTag);
            }
        }

        return newEntry;
    }

    private static boolean needsDamageTag(String id)
    {
        ItemStack stack = InventoryUtils.getItemStackFromString(id);

        return stack != null && !stack.isEmpty() && stack.isDamageable();
    }

    private static NbtCompound processComponentsTag(NbtCompound nbt, String itemId, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound outNbt = new NbtCompound();
        NbtCompound beNbt = new NbtCompound();
        NbtCompound dispNbt = new NbtCompound();
        boolean needsDamage = needsDamageTag(itemId);

        for (String key : nbt.getKeys())
        {
            switch (key)
            {
                case "minecraft:attribute_modifiers" -> outNbt.put("AttributeModifiers", processAttributes(nbt.get(key), minecraftDataVersion, registryManager));
                case "minecraft:banner_patterns" ->
                {
                    beNbt.put("Patterns", processBannerPatterns(nbt.get(key)));
                    beNbt.putString("id", "minecraft:banner");
                }
                case "minecraft:bees" ->
                {
                    beNbt.put("Bees", processBeesTag(nbt.get(key), minecraftDataVersion, registryManager));
                    beNbt.putString("id", itemId);
                }
                case "minecraft:block_state" -> outNbt.put("BlockStateTag", processBlockState(nbt.get(key)));
                case "minecraft:block_entity_data" -> processBlockEntityData(nbt.get(key), beNbt, minecraftDataVersion, registryManager);       // TODO --> check that this works or not
                case "minecraft:bucket_entity_data" -> processBucketEntityData(nbt.get(key), beNbt, minecraftDataVersion, registryManager);
                case "minecraft:bundle_contents" -> outNbt.put("Items", processItemsTag(nbt.getListOrEmpty(key), minecraftDataVersion, registryManager));
                case "minecraft:can_break" -> outNbt.put("CanDestroy", nbt.get(key));
                case "minecraft:can_place_on" -> outNbt.put("CanPlaceOn", nbt.get(key));
                case "minecraft:container" ->
                {
                    if (itemId.contains("decorated_pot"))
                    {
                        beNbt.put("item", processDecoratedPot_Nested(nbt.getListOrEmpty(key), minecraftDataVersion, registryManager));
                    }
                    else
                    {
                        beNbt.put("Items", processItemsTag_Nested(nbt.getListOrEmpty(key), minecraftDataVersion, registryManager));
                    }
                    if (itemId.contains("shulker"))
                    {
                        beNbt.putString("id", "minecraft:shulker_box");
                    }
                    else
                    {
                        beNbt.putString("id", itemId);
                    }
                }
                case "minecraft:charged_projectiles" ->
                {
                    outNbt.put("ChargedProjectiles", processChargedProjectile(nbt.get(key), minecraftDataVersion, registryManager));
                    outNbt.putBoolean("Charged", true);
                }
                case "minecraft:container_loot" ->
                {
                    beNbt.put("LootTable", processLootTable(nbt.get(key)));
                    beNbt.putString("id", itemId);
                }
                case "minecraft:custom_data" -> processCustomData(nbt.get(key), outNbt);
                case "minecraft:custom_model_data" -> outNbt.putInt("CustomModelData", nbt.getInt(key, 0));
                case "minecraft:custom_name" -> dispNbt.putString("Name", processCustomNameTag(nbt, key, registryManager));
                case "minecraft:damage" -> outNbt.putInt("Damage", nbt.getInt(key, 0));
                case "minecraft:debug_stick_state" -> outNbt.put("DebugProperty", nbt.get(key));
                case "minecraft:dyed_color" -> dispNbt.putInt("color", processDyedColor(nbt.get(key)));
                case "minecraft:enchantments" -> outNbt.put("Enchantments", processEnchantments(nbt.get(key), true, true));
                case "minecraft:entity_data" -> outNbt.put("EntityTag", downgradeEntity_to_1_20_4((NbtCompound) nbt.get(key), minecraftDataVersion, registryManager));
                case "minecraft:stored_enchantments" -> outNbt.put("StoredEnchantments", processEnchantments(nbt.get(key), true, true));
                case "minecraft:fireworks" -> outNbt.put("Fireworks", processFireworks(nbt.get(key)));
                case "minecraft:firework_explosion" -> outNbt.put("Explosion", processFireworkExplosion(nbt.get(key)));
                // "minecraft:hide_additional_tooltip" --> ignore
                case "minecraft:instrument" -> outNbt.put("instrument", processInstrument(nbt.get(key)));
                case "minecraft:item_name" -> dispNbt.putString("Name", processItemName(nbt.get(key), registryManager));
                case "minecraft:lock" ->
                {
                    beNbt.put("Lock", nbt.get(key));
                    beNbt.putString("id", itemId);
                }
                case "minecraft:lodestone_tracker" -> processLodestoneTracker(nbt.get(key), outNbt);
                case "minecraft:lore" -> dispNbt.put("Lore", nbt.get(key));
                case "minecraft:map_id" -> outNbt.put("map", processMapId(nbt.get(key)));
                case "minecraft:map_color" -> dispNbt.put("MapColor", nbt.get(key));
                case "minecraft:map_decorations" -> outNbt.put("Decorations", processMapDecorations(nbt.get(key)));
                case "minecraft:note_block_sound" -> beNbt.put("note_block_sound", nbt.get(key));
                case "minecraft:pot_decorations" ->
                {
                    beNbt.put("sherds", processSherds(nbt.get(key)));
                    beNbt.putString("id", itemId);
                }
                case "minecraft:potion_contents" -> processPotions(nbt.get(key), outNbt);
                case "minecraft:profile" -> outNbt.put("SkullOwner", processSkullProfile(nbt.get(key), dispNbt, minecraftDataVersion, registryManager));
                case "minecraft:repair_cost" -> outNbt.putInt("RepairCost", nbt.getInt(key, 0));
                case "minecraft:recipes" -> outNbt.put("Recipes", processRecipes(nbt.get(key)));
                case "minecraft:suspicious_stew_effects" -> outNbt.put("effects", processSuspiciousStewEffects(nbt.get(key)));
                case "minecraft:trim" -> outNbt.put("Trim", processTrim(nbt.get(key)));
                case "minecraft:writable_book_content" ->
                {
                    NbtCompound bookNbt = nbt.getCompoundOrEmpty(key);
                    bookNbt = processWritableBookContent(bookNbt, minecraftDataVersion, registryManager);
                    for (String bookKey : bookNbt.getKeys())
                    {
                        outNbt.put(bookKey, bookNbt.get(bookKey));
                    }
                }
                case "minecraft:written_book_content" ->
                {
                    NbtCompound bookNbt = nbt.getCompoundOrEmpty(key);
                    bookNbt = processWrittenBookContent(bookNbt, minecraftDataVersion, registryManager);
                    for (String bookKey : bookNbt.getKeys())
                    {
                        outNbt.put(bookKey, bookNbt.get(bookKey));
                    }
                }
                case "minecraft:unbreakable" -> outNbt.putBoolean("Unbreakable", processUnbreakable(nbt.get(key)));
            }
        }
        if (!beNbt.isEmpty())
        {
            outNbt.put("BlockEntityTag", beNbt);
        }
        if (!dispNbt.isEmpty())
        {
            outNbt.put("display", dispNbt);
        }
        if (!outNbt.contains("RepairCost") && (itemId.equals("minecraft:dragon_head") || needsDamage))
        {
            outNbt.putInt("RepairCost", 0);
        }
        if (!outNbt.contains("Damage") && needsDamage)
        {
            outNbt.putInt("Damage", 0);
        }

        return outNbt;
    }

    private static void processCustomData(NbtElement oldNbt, NbtCompound outNbt)
    {
        NbtCompound origData = (NbtCompound) oldNbt;

        for (String keyData : origData.getKeys())
        {
            outNbt.put(keyData, origData.get(keyData));
        }
    }

    private static void processLodestoneTracker(NbtElement oldEle, NbtCompound outNbt)
    {
        NbtCompound oldNbt = (NbtCompound) oldEle;

        if (oldNbt.contains("tracked"))
        {
            outNbt.putBoolean("LodestoneTracked", oldNbt.getBoolean("tracked", false));
        }
        if (oldNbt.contains("target"))
        {
            NbtCompound target = oldNbt.getCompoundOrEmpty("target");

            outNbt.put("LodestoneDimension", target.get("dimension"));
            outNbt.put("LodestonePos", target.get("pos"));
        }
    }

    private static void processBucketEntityData(NbtElement oldTags, NbtCompound beNbt, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound oldNbt = (NbtCompound) oldTags;

//        NbtCompound newNbt = downgradeEntity_to_1_20_4(oldNbt, minecraftDataVersion, registryManager);
//        beNbt.copyFrom(newNbt);

        for (String key : oldNbt.getKeys())
        {
            beNbt.put(key, oldNbt.get(key));
        }
    }

    private static void processPotions(NbtElement oldPots, NbtCompound outNbt)
    {
        NbtCompound oldNbt = (NbtCompound) oldPots;

        if (oldNbt.contains("potion"))
        {
            outNbt.putString("Potion", oldNbt.getString("potion", ""));
        }
        if (oldNbt.contains("custom_color"))
        {
            outNbt.put("CustomPotionColor", oldNbt.get("custom_color"));
        }
        if (oldNbt.contains("custom_effects"))
        {
            outNbt.put("custom_potion_effects", oldNbt.get("custom_effects"));
        }
    }

    private static NbtElement processMapDecorations(NbtElement oldDeco)
    {
        NbtCompound oldTag = (NbtCompound) oldDeco;
        NbtList newTags = new NbtList();

        for (String key : oldTag.getKeys())
        {
            NbtCompound entryOld = oldTag.getCompoundOrEmpty(key);
            NbtCompound entryNew = new NbtCompound();

            entryNew.putString("id", key);
            entryNew.putDouble("x", entryOld.contains("x") ? entryOld.getDouble("x", 0d) : 0.0);
            entryNew.putDouble("z", entryOld.contains("z") ? entryOld.getDouble("z", 0d) : 0.0);
            entryNew.putDouble("rot", entryOld.contains("rotation") ? (double) entryOld.getFloat("rotation", 0f) : 0.0);
            entryNew.putByte("type", (byte) (entryOld.contains("type") ? convertMapDecoration(entryOld.getString("type", "")) : 0));

            newTags.add(entryNew);
        }

        return newTags;
    }

    private static int convertMapDecoration(String type)
    {
        return switch (type)
        {
            case "minecraft:player" -> 0;
            case "minecraft:frame" -> 1;
            case "minecraft:red_marker" -> 2;
            case "minecraft:blue_marker" -> 3;
            case "minecraft:target_x" -> 4;
            case "minecraft:target_point" -> 5;
            case "minecraft:player_off_map" -> 6;
            case "minecraft:player_off_limits" -> 7;
            case "minecraft:mansion" -> 8;
            case "minecraft:monument" -> 9;
            case "minecraft:banner_white" -> 10;
            case "minecraft:banner_orange" -> 11;
            case "minecraft:banner_magenta" -> 12;
            case "minecraft:banner_light_blue" -> 13;
            case "minecraft:banner_yellow" -> 14;
            case "minecraft:banner_lime" -> 15;
            case "minecraft:banner_pink" -> 16;
            case "minecraft:banner_gray" -> 17;
            case "minecraft:banner_light_gray" -> 18;
            case "minecraft:banner_cyan" -> 19;
            case "minecraft:banner_purple" -> 20;
            case "minecraft:banner_blue" -> 21;
            case "minecraft:banner_brown" -> 22;
            case "minecraft:banner_green" -> 23;
            case "minecraft:banner_red" -> 24;
            case "minecraft:banner_black" -> 25;
            case "minecraft:red_x" -> 26;
            case "minecraft:village_desert" -> 27;
            case "minecraft:village_plains" -> 28;
            case "minecraft:village_savanna" -> 29;
            case "minecraft:village_snowy" -> 30;
            case "minecraft:village_taiga" -> 31;
            case "minecraft:jungle_temple" -> 32;
            case "minecraft:swamp_hut" -> 33;
            default -> 0;
        };
    }

    private static NbtElement processSherds(NbtElement oldSherds)
    {
        return oldSherds;
    }

    private static NbtElement processLootTable(NbtElement oldLoot)
    {
        NbtCompound oldTable = (NbtCompound) oldLoot;
        NbtCompound newTable = new NbtCompound();

        if (oldTable.contains("loot_table"))
        {
            NbtCompound loot = oldTable.getCompoundOrEmpty("loot_table");
            newTable.copyFrom(loot);
        }
        if (oldTable.contains("seed"))
        {
            newTable.putLong("LootTableSeed", oldTable.getLong("seed", 0L));
        }

        return newTable;
    }

    private static String processItemName(NbtElement oldName, DynamicRegistryManager registryManager)
    {
        if (oldName != null)
        {
            return oldName.toString();
        }

        return "minecraft:air";
    }

    private static int processDyedColor(NbtElement oldDye)
    {
        NbtCompound oldColor = (NbtCompound) oldDye;

        if (oldColor.contains("rgb"))
        {
            return oldColor.getInt("rgb", 10511680);
        }

        // Default
        return 10511680;
    }

    private static NbtElement processRecipes(NbtElement oldRecipes)
    {
        return oldRecipes;
    }

    private static NbtElement processInstrument(NbtElement oldGoat)
    {
        return oldGoat;
    }

    private static NbtElement processSuspiciousStewEffects(NbtElement oldEffects)
    {
        return oldEffects;
    }

    private static NbtElement processMapId(NbtElement oldMapId)
    {
        return oldMapId;
    }

    private static NbtElement processTrim(NbtElement oldTrim)
    {
        return oldTrim;
    }

    private static NbtList processChargedProjectile(NbtElement oldProjectiles, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtList oldNbt = (NbtList) oldProjectiles;
        NbtList newNbt = new NbtList();

        for (int i = 0; i < oldNbt.size(); i++)
        {
            NbtCompound itemEntry = oldNbt.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            if (!itemEntry.contains("id"))
            {
                continue;
            }
            String idName = itemEntry.getString("id", "");
            newEntry.putString("id", idName);
            newEntry.putByte("Count", (byte) (itemEntry.contains("count") ? itemEntry.getInt("count") : 1));
            if (itemEntry.contains("components"))
            {
                newEntry.put("tag", processComponentsTag(itemEntry.getCompoundOrEmpty("components"), idName, minecraftDataVersion, registryManager));
            }

            newNbt.add(newEntry);
        }

        return newNbt;
    }

    private static boolean processUnbreakable(NbtElement oldNbt)
    {
        NbtCompound oldUnbr = (NbtCompound) oldNbt;

        if (oldUnbr.contains("show_in_tooltip"))
        {
            return oldUnbr.getBoolean("show_in_tooltip", false);
        }

        return false;
    }

    private static void processBlockEntityData(NbtElement oldBeData, NbtCompound beNbt, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound newData = downgradeBlockEntity_to_1_20_4((NbtCompound) oldBeData, minecraftDataVersion, registryManager);

        for (String key : newData.getKeys())
        {
            beNbt.put(key, newData.get(key));
        }
    }

    private static NbtElement processDecoratedPot(NbtElement oldPot, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound oldNbt = (NbtCompound) oldPot;
        NbtCompound newNbt = new NbtCompound();

        for (String key : oldNbt.getKeys())
        {
            switch (key)
            {
                case "id" -> newNbt.putString("id", oldNbt.getString("id", ""));
                case "count" -> newNbt.putByte("Count", (byte) oldNbt.getInt("count", 1));
                case "components" -> newNbt.put("tag", processComponentsTag(oldNbt.getCompoundOrEmpty("components"), oldNbt.getString("id", ""), minecraftDataVersion, registryManager));
            }
        }

        if (!newNbt.contains("tag") && oldNbt.contains("id") && needsDamageTag(oldNbt.getString("id", "")))
        {
            NbtCompound newTag = new NbtCompound();
            newTag.putInt("Damage", 0);
            newNbt.put("tag", newTag);
        }

        return newNbt;
    }

    private static NbtElement processEnchantments(NbtElement oldNbt, boolean fullId, boolean shortInt)
    {
        NbtCompound oldEnchants = (NbtCompound) oldNbt;
        NbtCompound oldLevels = oldEnchants.getCompoundOrEmpty("levels");
        NbtList newEnchants = new NbtList();
        boolean showTooltip = false;

        if (oldEnchants.contains("show_in_tooltip"))
        {
            showTooltip = oldEnchants.getBoolean("show_in_tooltip", false);
            // todo - Has no function under 1.20.4
        }

        for (String key : oldLevels.getKeys())
        {
            NbtCompound newEntry = new NbtCompound();
            Identifier id = Identifier.of(key);
            if (shortInt)
            {
                newEntry.putShort("lvl", (short) oldLevels.getInt(key, 1));
            }
            else
            {
                newEntry.putInt("lvl", oldLevels.getInt(key, 1));
            }
            newEntry.putString("id", fullId ? id.toString() : id.getPath());
            newEnchants.add(newEntry);
        }

        return newEnchants;
    }

    private static String processCustomNameTag(NbtCompound nameTag, String key, @Nonnull DynamicRegistryManager registry)
    {
        // Sometimes this is missing the 'text' designation ?

        /*
        String oldNameString = nameTag.getString(key);
        MutableText oldCustomName = Text.Serialization.fromJson(oldNameString, registryManager);

        //System.out.printf("processCustomNameTag(): oldName [%s], text: [%s], newString [%s]\n", oldNameString, oldCustomName.getString(), newCustomName);

         */

        MutableText oldName = (MutableText) NbtBlockUtils.getCustomNameFromNbt(nameTag, registry, key);
        return legacyTextDeserializer(oldName, registry);
    }

    private static String legacyTextDeserializer(MutableText oldText, @Nonnull DynamicRegistryManager registry)
    {
        try
        {
            JsonElement element = TextCodecs.CODEC.encodeStart(registry.getOps(JsonOps.INSTANCE), oldText).getOrThrow();
            return new GsonBuilder().disableHtmlEscaping().create().toJson(element);
        }
        catch (Exception err)
        {
            Litematica.LOGGER.error("legacyTextDeserializer: Failed to convert MutableText to JSON; (falling back to just 'getString'); {}", err.getLocalizedMessage());
            return oldText.getString();
        }
    }

    private static @Nullable MutableText legacyTextSerializer(String json, @Nonnull DynamicRegistryManager registry)
    {
        try
        {
            return (MutableText) TextCodecs.CODEC.parse(registry.getOps(JsonOps.INSTANCE), JsonParser.parseString(json)).getOrThrow();
        }
        catch (Exception err)
        {
            Litematica.LOGGER.error("legacyTextSerializer: Failed to convert JSON to MutableText; {}", err.getLocalizedMessage());
            return null;
        }
    }

    private static NbtElement processBlockState(NbtElement bsTag)
    {
        NbtCompound oldBS = (NbtCompound) bsTag;
        NbtCompound newBS = new NbtCompound();

        for (String key : oldBS.getKeys())
        {
            newBS.put(key, oldBS.get(key));
        }

        return bsTag;
    }

    private static NbtElement processFireworks(NbtElement rocket)
    {
        NbtCompound oldRocket = (NbtCompound) rocket;
        NbtCompound newRocket = new NbtCompound();

        if (oldRocket.contains("flight_duration"))
        {
            newRocket.putByte("Flight", oldRocket.getByte("flight_duration", (byte) 1));
        }
        if (oldRocket.contains("explosions"))
        {
            NbtList oldExplosions = oldRocket.getListOrEmpty("explosions");
            NbtList newExplosions = new NbtList();

            for (int i = 0; i < oldExplosions.size(); i++)
            {
                newExplosions.add(processFireworkExplosion(oldExplosions.getCompoundOrEmpty(i)));
            }

            newRocket.put("Explosions", newExplosions);
        }

        return newRocket;
    }

    private static NbtElement processFireworkExplosion(NbtElement explosion)
    {
        NbtCompound oldExplosion = (NbtCompound) explosion;
        NbtCompound newExplosion = new NbtCompound();

        if (oldExplosion.contains("shape"))
        {
            newExplosion.putByte("Type", (byte) convertFireworkShape(oldExplosion.getString("shape", "")));
        }
        if (oldExplosion.contains("colors"))
        {
            newExplosion.putIntArray("Colors", oldExplosion.getIntArray("colors").orElse(new int[0]));
        }
        if (oldExplosion.contains("fade_colors"))
        {
            newExplosion.putIntArray("FadeColors", oldExplosion.getIntArray("fade_colors").orElse(new int[0]));
        }
        if (oldExplosion.contains("has_trail"))
        {
            newExplosion.putBoolean("Trail", oldExplosion.getBoolean("has_trail", false));
        }
        if (oldExplosion.contains("has_twinkle"))
        {
            newExplosion.putBoolean("Flicker", oldExplosion.getBoolean("has_twinkle", false));
        }

        return newExplosion;
    }

    private static int convertFireworkShape(String shape)
    {
        return switch (shape)
        {
            case "small_ball" -> 0;
            case "large_ball" -> 1;
            case "star" -> 2;
            case "creeper" -> 3;
            case "burst" -> 4;
            default -> 0;
        };
    }

    private static NbtElement processRecordItem(NbtElement itemIn, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound oldRecord = (NbtCompound) itemIn;
        NbtCompound recordOut = new NbtCompound();

        recordOut.putString("id", oldRecord.getString("id", ""));
        recordOut.putByte("Count", (byte) oldRecord.getInt("count", 1));

        if (oldRecord.contains("components"))
        {
            recordOut.put("tag", processComponentsTag(oldRecord.getCompoundOrEmpty("components"), oldRecord.getString("id", ""), minecraftDataVersion, registryManager));
        }

        return recordOut;
    }

    private static NbtElement processBookTag(NbtElement bookNbt, int minecraftDataVersion, DynamicRegistryManager registryManager)
    {
        NbtCompound oldBook = (NbtCompound) bookNbt;
        NbtCompound newBook = new NbtCompound();

        newBook.putString("id", oldBook.getString("id", ""));
        newBook.putByte("Count", (byte) oldBook.getInt("count", 1));

        if (oldBook.contains("Page"))
        {
            newBook.putInt("Page", oldBook.getInt("Page", 1));
        }
        if (oldBook.contains("components"))
        {
            newBook.put("tag", processComponentsTag(oldBook.getCompoundOrEmpty("components"), oldBook.getString("id", ""), minecraftDataVersion, registryManager));
        }

        return newBook;
    }

    private static NbtCompound processWritableBookContent(NbtCompound bookNbt, int minecraftDataVersion, @Nonnull DynamicRegistryManager registry)
    {
        NbtCompound newBook = new NbtCompound();
        NbtList newPages = new NbtList();

        if (bookNbt.contains("pages"))
        {
            NbtList pages = bookNbt.getListOrEmpty("pages");

            for (int i = 0; i < pages.size(); i++)
            {
                NbtCompound page = pages.getCompoundOrEmpty(i);
                String oldPage = page.getString("raw", "");

                try
                {
                    MutableText oldText = legacyTextSerializer(oldPage, registry);
//                    MutableText oldText = Text.Serialization.fromJson(oldPage, registry);
//                    String newPage = Text.Serialization.toJsonString(oldText, registry);
                    String newPage = legacyTextDeserializer(oldText, registry);
                    newPages.add(i, NbtString.of(newPage));
                }
                catch (Exception e)
                {
                    newPages.add(i, NbtString.of(oldPage));
                }
            }
        }
        if (!newPages.isEmpty())
        {
            newBook.put("pages", newPages);
        }

        return newBook;
    }

    private static NbtCompound processWrittenBookContent(NbtCompound bookNbt, int minecraftDataVersion, @Nonnull DynamicRegistryManager registry)
    {
        NbtCompound newBook = new NbtCompound();
        NbtCompound filtered = new NbtCompound();
        NbtList newPages = new NbtList();

        if (bookNbt.contains("author"))
        {
            newBook.putString("author", bookNbt.getString("author", "?"));
        }
        if (bookNbt.contains("title"))
        {
            NbtCompound title = bookNbt.getCompoundOrEmpty("title");
            newBook.putString("title", title.getString("raw", ""));
        }
        if (bookNbt.contains("resolved"))
        {
            newBook.putBoolean("resolved", bookNbt.getBoolean("resolved", false));
        }
        if (bookNbt.contains("generation"))
        {
            newBook.putInt("generation", bookNbt.getInt("generation", 1));
        }

        if (bookNbt.contains("pages"))
        {
            NbtList pages = bookNbt.getListOrEmpty("pages");

            for (int i = 0; i < pages.size(); i++)
            {
                NbtCompound page = pages.getCompoundOrEmpty(i);
                String oldPage = page.getString("raw", "");

                if (page.contains("filtered"))
                {
                    String filterPage = page.getString("filtered", "");
                    try
                    {
                        MutableText filteredText = legacyTextSerializer(filterPage, registry);
//                        MutableText filteredText = Text.Serialization.fromJson(filterPage, registry);
//                        String newFilterPage = Text.Serialization.toJsonString(filteredText, registry);
                        String newFilterPage = legacyTextDeserializer(filteredText, registry);
                        filtered.putString(filterPage, newFilterPage);
                        // This seems like A terrible idea
                    }
                    catch (Exception e)
                    {
                        filtered.putString(filterPage, filterPage);
                    }
                }
                try
                {
                    MutableText oldText = legacyTextSerializer(oldPage, registry);
//                    MutableText oldText = Text.Serialization.fromJson(oldPage, registry);
//                    String newPage = Text.Serialization.toJsonString(oldText, registry);
                    String newPage = legacyTextDeserializer(oldText, registry);
                    newPages.add(i, NbtString.of(newPage));
                }
                catch (Exception e)
                {
                    newPages.add(i, NbtString.of(oldPage));
                }
            }
        }
        if (!newPages.isEmpty())
        {
            newBook.put("pages", newPages);
        }
        if (!filtered.isEmpty())
        {
            newBook.put("filtered_pages", filtered);
        }

        return newBook;
    }

    private static NbtElement processBannerPatterns(NbtElement oldPatterns)
    {
        NbtList newList = new NbtList();
        NbtList oldList = (NbtList) oldPatterns;

        for (int i = 0; i < oldList.size(); i++)
        {
            NbtCompound oldEntry = oldList.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();
            String color = oldEntry.getString("color", "");
            String pattern = oldEntry.getString("pattern", "");
            DyeColor dye = DyeColor.byId(color, DyeColor.WHITE);

            newEntry.putString("Pattern", convertBannerPattern(pattern));
            newEntry.putInt("Color", dye.getIndex());

            newList.add(newEntry);
        }

        return newList;
    }

    private static String convertBannerPattern(String patternId)
    {
        return switch (patternId)
        {
            case "minecraft:base" -> "b";
            case "minecraft:square_bottom_left" -> "bl";
            case "minecraft:square_bottom_right" -> "br";
            case "minecraft:square_top_left" -> "tl";
            case "minecraft:square_top_right" -> "tr";
            case "minecraft:stripe_bottom" -> "bs";
            case "minecraft:stripe_top" -> "ts";
            case "minecraft:stripe_left" -> "ls";
            case "minecraft:stripe_right" -> "rs";
            case "minecraft:stripe_center" -> "cs";
            case "minecraft:stripe_middle" -> "ms";
            case "minecraft:stripe_downright" -> "drs";
            case "minecraft:stripe_downleft" -> "dls";
            case "minecraft:small_stripes" -> "ss";
            case "minecraft:cross" -> "cr";
            case "minecraft:straight_cross" -> "sc";
            case "minecraft:triangle_bottom" -> "bt";
            case "minecraft:triangle_top" -> "tt";
            case "minecraft:triangles_bottom" -> "bts";
            case "minecraft:triangles_top" -> "tts";
            case "minecraft:diagonal_left" -> "ld";
            case "minecraft:diagonal_up_right" -> "rd";
            case "minecraft:diagonal_up_left" -> "lud";
            case "minecraft:diagonal_right" -> "rud";
            case "minecraft:circle" -> "mc";
            case "minecraft:rhombus" -> "mr";
            case "minecraft:half_vertical" -> "vh";
            case "minecraft:half_horizontal" -> "hh";
            case "minecraft:half_vertical_right" -> "vhr";
            case "minecraft:half_horizontal_bottom" -> "hhb";
            case "minecraft:border" -> "bo";
            case "minecraft:curly_border" -> "cbo";
            case "minecraft:gradient" -> "gra";
            case "minecraft:gradient_up" -> "gru";
            case "minecraft:bricks" -> "bri";
            case "minecraft:globe" -> "glb";
            case "minecraft:creeper" -> "cre";
            case "minecraft:skull" -> "sku";
            case "minecraft:flower" -> "flo";
            case "minecraft:mojang" -> "moj";
            case "minecraft:piglin" -> "pig";
            // Doesn't exist in 1.20.4
            //case "minecraft:flow" -> "flo";
            //case "minecraft:guster" -> "gus";
            default -> "b";
        };
    }

    private static NbtElement processSkullProfile(NbtElement oldProfile, NbtCompound dispNbt, int minecraftDataVersion, @Nonnull DynamicRegistryManager registry)
    {
        NbtCompound profile = (NbtCompound) oldProfile;
        NbtCompound newProfile = new NbtCompound();
        String customName1 = dispNbt.getString("Name", "");         // Can be either an Item Name or Custom Name Data Component
        String customName2 = dispNbt.getString("CustomName", "");   // Only if invoked without it being stored in a Chest
        String name = profile.getString("name", "");                // The regular Skull Owner Name
        //UUID uuid = profile.getUuid("id");
        UUID uuid = profile.get("id", Uuids.CODEC, registry.getOps(NbtOps.INSTANCE)).orElse(Util.NIL_UUID);

//        LOGGER.debug("processSkullProfile(): oldNBT [{}]", profile.toString());
        if (name.isEmpty() && !customName1.isEmpty())
        {
            try
            {
//                Text disp = Text.Serialization.fromJson(customName1, registry);
                MutableText disp = legacyTextSerializer(customName1, registry);

                if (disp != null)
                {
                    name = disp.getLiteralString();
                }

                if (name == null)
                {
                    name = customName1;
                }

//                LOGGER.debug("processSkullProfile(): customName1 [{}], disp [{}] // name [{}]", customName1, disp != null ? disp.getString() : "<null>", name);
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("processSkullProfile(): Exception deserializing CustomName1 for Head Name.");
                name = customName1;
            }
        }
        else if (name.isEmpty() && !customName2.isEmpty())
        {
            try
            {
//                Text disp = Text.Serialization.fromJson(customName2, registry);
                MutableText disp = legacyTextSerializer(customName2, registry);

                if (disp != null)
                {
                    name = disp.getLiteralString();
                }

                if (name == null)
                {
                    name = customName2;
                }

//                LOGGER.debug("processSkullProfile(): customName2 [{}], disp[{}] // name [{}]", customName2, disp != null ? disp.getString() : "<null>", name);
            }
            catch (Exception e)
            {
                Litematica.LOGGER.warn("processSkullProfile(): Exception deserializing CustomName2 for Head Name.");
                name = customName2;
            }
        }

        newProfile.putString("Name", name);
        //newProfile.putUuid("Id", uuid);
        newProfile.put("Id", Uuids.INT_STREAM_CODEC, uuid);

//        LOGGER.debug("processSkullProfile(): name [{}], uuid [{}]", name, uuid.toString());

        NbtList properties = profile.getListOrEmpty("properties");
        NbtCompound newProperties = new NbtCompound();

        for (int i = 0; i < properties.size(); i++)
        {
            NbtCompound property = properties.getCompoundOrEmpty(i);
            String propName = property.getString("name", "");
            String propValue = property.getString("value", "");

//            LOGGER.debug("processSkullProfile(): entry[{}], name [{}]", i, propName);

            if (propName.equals("textures"))
            {
                NbtList textures = new NbtList();
                NbtCompound value = new NbtCompound();
                value.putString("Value", propValue);
                textures.add(value);
                newProperties.put("textures", textures);
            }
        }

        newProfile.put("Properties", newProperties);
//        LOGGER.debug("processSkullProfile(): newNBT [{}]", newProfile.toString());

        return newProfile;
    }

    private static NbtElement processFlowerPos(NbtCompound oldNbt, String key, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtCompound flowerOut = new NbtCompound();
        //BlockPos flowerPos = NbtHelper.toBlockPos(oldNbt, key).orElse(null);
        BlockPos flowerPos = oldNbt.get(key, BlockPos.CODEC, registryManager.getOps(NbtOps.INSTANCE)).orElse(null);

        if (flowerPos != null)
        {
            flowerOut.putInt("X", flowerPos.getX());
            flowerOut.putInt("Y", flowerPos.getY());
            flowerOut.putInt("Z", flowerPos.getZ());
        }

        return flowerOut;
    }

    private static NbtElement processBeesTag(NbtElement beesTag, int minecraftDataVersion, @Nonnull DynamicRegistryManager registryManager)
    {
        NbtList oldBees = (NbtList) beesTag;
        NbtList newBees = new NbtList();

        for (int i = 0; i < oldBees.size(); i++)
        {
            NbtCompound oldEntry = oldBees.getCompoundOrEmpty(i);
            NbtCompound newEntry = new NbtCompound();

            newEntry.putInt("TicksInHive", oldEntry.getInt("ticks_in_hive", 0));
            newEntry.putInt("MinOccupationTicks", oldEntry.getInt("min_ticks_in_hive", 0));
            newEntry.put("EntityData", downgradeEntity_to_1_20_4(oldEntry.getCompoundOrEmpty("entity_data"), minecraftDataVersion, registryManager));

            newBees.add(newEntry);
        }

        return newBees;
    }
}
