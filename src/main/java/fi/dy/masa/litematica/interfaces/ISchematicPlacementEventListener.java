package fi.dy.masa.litematica.interfaces;

import javax.annotation.Nullable;
import com.google.gson.JsonObject;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementEventHandler;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;

/**
 * Here is the interface for the event listener for {@link SchematicPlacementEventHandler}
 */
public interface ISchematicPlacementEventListener
{
    /**
     * Event callback when a Schematic Placement object is initialized.
     * @param placement ()
     */
    void onPlacementInit(SchematicPlacement placement);

    /**
     * Event callback when a Schematic Placement object is initialized.
     * @param subRegion ()
     */
    void onSubRegionInit(SubRegionPlacement subRegion);

    /**
     * Event callback when a Schematic Placement is created based on a Litematic.
     * @param placement ()
     * @param schematic ()
     * @param origin ()
     * @param name ()
     * @param enabled ()
     * @param enableRender ()
     */
    void onPlacementCreateFor(SchematicPlacement placement, LitematicaSchematic schematic, BlockPos origin, String name, boolean enabled, boolean enableRender);

    /**
     * Event callback when a Schematic Placement is created for Litematic Conversion purposes; such as Converting from a Sponge Schematic.
     * @param placement ()
     * @param schematic ()
     * @param origin ()
     */
    void onPlacementCreateForConversion(SchematicPlacement placement, LitematicaSchematic schematic, BlockPos origin);

    /**
     * Event callback when a Schematic Placement is created from loading it via the JSON Config.
     * @param placement ()
     * @param schematic ()
     * @param origin ()
     * @param name ()
     * @param enabled ()
     * @param enableRender ()
     */
    void onPlacementCreateFromJson(SchematicPlacement placement, LitematicaSchematic schematic, BlockPos origin, String name, BlockRotation rotation, BlockMirror mirror, boolean enabled, boolean enableRender);

    /**
     * Event callback when a Schematic Placement is created from loading it via an NbtCompound (Litematic Transmit).
     * @param placement ()
     * @param schematic ()
     * @param origin ()
     * @param name ()
     * @param enabled ()
     * @param enableRender ()
     */
    void onPlacementCreateFromNbt(SchematicPlacement placement, LitematicaSchematic schematic, BlockPos origin, String name, BlockRotation rotation, BlockMirror mirror, boolean enabled, boolean enableRender);

    /**
     * Event callback when a Schematic Placement is being saved to JSON.
     * @param placement ()
     * @param json ()
     */
    void onSavePlacementToJson(SchematicPlacement placement, JsonObject json);

    /**
     * Event callback when a Schematic Placement is being saved to NBT (To Servux for pasting, etc.).
     * @param placement ()
     * @param nbt ()
     */
    void onSavePlacementToNbt(SchematicPlacement placement, NbtCompound nbt);

    /**
     * Event callback when a Schematic Placement is created from loading it via the JSON Config.
     * @param subRegion ()
     * @param origin ()
     * @param name ()
     * @param enabled ()
     * @param enableRender ()
     */
    void onSubRegionCreateFromJson(SubRegionPlacement subRegion, BlockPos origin, String name, BlockRotation rotation, BlockMirror mirror, boolean enabled, boolean enableRender);

    /**
     * Event callback when a Schematic Placement is being saved to JSON.
     * @param subRegion ()
     * @param json ()
     */
    void onSaveSubRegionToJson(SubRegionPlacement subRegion, JsonObject json);

    /**
     * Event callback when a Schematic Placement is toggled Locked/Unlocked.
     * @param placement ()
     * @param toggle ()
     */
    void onToggleLocked(SchematicPlacement placement, boolean toggle);

    /**
     * Event callback when a Schematic Placement is toggled Enabled/Disabled.
     * @param placement ()
     * @param toggle ()
     */
    void onSetEnabled(SchematicPlacement placement, boolean toggle);

    /**
     * Event callback when a Schematic Placement is toggled to Render.
     * @param placement ()
     * @param toggle ()
     */
    void onSetRender(SchematicPlacement placement, boolean toggle);

    /**
     * Event callback when a Schematic Placement is renamed.
     * @param placement ()
     * @param name ()
     */
    void onSetName(SchematicPlacement placement, String name);

    /**
     * Event callback when a Schematic Placement adjusts the Block Origin Pos..
     * @param placement ()
     * @param origin ()
     */
    void onSetOrigin(SchematicPlacement placement, BlockPos origin);

    /**
     * Event callback when a Schematic Placement's Block Mirroring is changed.
     * @param placement ()
     * @param mirror ()
     */
    void onSetMirror(SchematicPlacement placement, BlockMirror mirror);

    /**
     * Event callback when a Schematic Placement's Block Rotation is changed.
     * @param placement ()
     * @param rotation ()
     */
    void onSetRotation(SchematicPlacement placement, BlockRotation rotation);

    /**
     * Event callback when a Schematic Placement is reset.
     * @param placement ()
     */
    void onPlacementReset(SchematicPlacement placement);

    /**
     * Event callback when a Schematic Placement is toggled Enabled/Disabled.
     * @param subRegion ()
     * @param toggle ()
     */
    void onSetSubRegionEnabled(SubRegionPlacement subRegion, boolean toggle);

    /**
     * Event callback when a Schematic Placement is toggled to Render.
     * @param subRegion ()
     * @param toggle ()
     */
    void onSetSubRegionRender(SubRegionPlacement subRegion, boolean toggle);

    /**
     * Event callback when a Schematic Placement adjusts the Block Origin Pos..
     * @param subRegion ()
     * @param origin ()
     */
    void onSetSubRegionOrigin(SubRegionPlacement subRegion, BlockPos origin);

    /**
     * Event callback when a Schematic Placement's Block Mirroring is changed.
     * @param subRegion ()
     * @param mirror ()
     */
    void onSetSubRegionMirror(SubRegionPlacement subRegion, BlockMirror mirror);

    /**
     * Event callback when a Schematic Placement's Block Rotation is changed.
     * @param subRegion ()
     * @param rotation ()
     */
    void onSetSubRegionRotation(SubRegionPlacement subRegion, BlockRotation rotation);

    /**
     * Event callback when a Schematic Placement is reset.
     * @param subRegion ()
     */
    void onSubRegionReset(SubRegionPlacement subRegion);

    /**
     * Event callback when a Schematic Placement is toggled as Selected.
     * Note that both of these are @Nullable.
     * @param prevPlacement ()
     * @param selected ()
     */
    void onPlacementSelected(@Nullable SchematicPlacement prevPlacement, @Nullable SchematicPlacement selected);

    /**
     * Event callback when a Schematic Placement is added to the Placement Manager.
     * @param placement ()
     */
    void onPlacementAdded(SchematicPlacement placement);

    /**
     * Event callback when a Schematic Placement is removed from the Placement Manager.
     * @param placement ()
     */
    void onPlacementRemoved(SchematicPlacement placement);

    /**
     * Event callback when a Schematic Placement was updated by any means.
     * @param placement ()
     */
    void onPlacementUpdated(SchematicPlacement placement);
}
