package fi.dy.masa.litematica.data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import com.google.gson.JsonObject;
import org.apache.commons.lang3.tuple.Pair;

import com.mojang.datafixers.util.Either;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.PiglinEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.DoubleInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IDataSyncer;
import fi.dy.masa.malilib.mixin.entity.IMixinAbstractHorseEntity;
import fi.dy.masa.malilib.mixin.entity.IMixinPiglinEntity;
import fi.dy.masa.malilib.mixin.network.IMixinDataQueryHandler;
import fi.dy.masa.malilib.network.ClientPlayHandler;
import fi.dy.masa.malilib.network.IPluginClientPlayHandler;
import fi.dy.masa.malilib.util.data.Constants;
import fi.dy.masa.malilib.util.InventoryUtils;
import fi.dy.masa.malilib.util.nbt.NbtKeys;
import fi.dy.masa.malilib.util.nbt.NbtUtils;
import fi.dy.masa.malilib.util.nbt.NbtView;
import fi.dy.masa.litematica.Litematica;
import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.network.ServuxLitematicaHandler;
import fi.dy.masa.litematica.network.ServuxLitematicaPacket;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;

public class EntitiesDataStorage implements IClientTickHandler, IDataSyncer
{
    private static final EntitiesDataStorage INSTANCE = new EntitiesDataStorage();

    public static EntitiesDataStorage getInstance()
    {
        return INSTANCE;
    }

    private final static ServuxLitematicaHandler<ServuxLitematicaPacket.Payload> HANDLER = ServuxLitematicaHandler.getInstance();
    private final MinecraftClient mc;
    //private int uptimeTicks = 0;
    private boolean servuxServer = false;
    private boolean hasInValidServux = false;
    private String servuxVersion;
    private final long chunkTimeoutMs = 5000;
    // Wait 5 seconds for loaded Client Chunks to receive Entity Data
    private boolean checkOpStatus = true;
    private boolean hasOpStatus = false;
    private long lastOpCheck = 0L;

    // Data Cache
    private final ConcurrentHashMap<BlockPos, Pair<Long, Pair<BlockEntity, NbtCompound>>> blockEntityCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer,  Pair<Long, Pair<Entity,      NbtCompound>>> entityCache      = new ConcurrentHashMap<>();
    private final long cacheTimeout = 4;
    private final long longCacheTimeout = 30;
    private boolean shouldUseLongTimeout = false;
    // Needs a long cache timeout for saving schematics
    private long serverTickTime = 0;
    // Requests to be executed
    private final Set<BlockPos> pendingBlockEntitiesQueue = new LinkedHashSet<>();
    private final Set<Integer> pendingEntitiesQueue = new LinkedHashSet<>();
    private final Set<ChunkPos> pendingChunks = new LinkedHashSet<>();
    private final Set<ChunkPos> completedChunks = new LinkedHashSet<>();
    private final Map<ChunkPos, Long> pendingChunkTimeout = new HashMap<>();
    // To save vanilla query packet transaction
    private final Map<Integer, Either<BlockPos, Integer>> transactionToBlockPosOrEntityId = new HashMap<>();
    private ClientWorld clientWorld;

    // Backup Chunk Saving task
    private boolean sentBackupPackets = false;
    private boolean receivedBackupPackets = false;
    private final HashMap<ChunkPos, Set<BlockPos>> pendingBackupChunk_BlockEntities = new HashMap<>();
    private final HashMap<ChunkPos, Set<Integer>>  pendingBackupChunk_Entities      = new HashMap<>();

    @Override
    @Nullable
    public World getWorld()
    {
        return fi.dy.masa.malilib.util.WorldUtils.getBestWorld(mc);
    }

    @Override
    public ClientWorld getClientWorld()
    {
        if (this.clientWorld == null)
        {
            this.clientWorld = this.mc.world;
        }

        return this.clientWorld;
    }

    private EntitiesDataStorage()
    {
        this.mc = MinecraftClient.getInstance();
    }

    @Override
    public void onClientTick(MinecraftClient mc)
    {
        long now = System.currentTimeMillis();
        //this.uptimeTicks++;

        if (now - this.serverTickTime > 50)
        {
            // In this block, we do something every server tick
            if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() == false)
            {
                this.serverTickTime = now;

                if (DataManager.getInstance().hasIntegratedServer() == false && this.hasServuxServer())
                {
                    this.servuxServer = false;
                    HANDLER.unregisterPlayReceiver();
                }

                if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue() == false)
                {
                    return;
                }
            }
            else if (DataManager.getInstance().hasIntegratedServer() == false &&
                    this.hasServuxServer() == false &&
                    this.hasInValidServux == false &&
                    this.getWorld() != null)
            {
                // Make sure we're Play Registered, and request Metadata
                HANDLER.registerPlayReceiver(ServuxLitematicaPacket.Payload.ID, HANDLER::receivePlayPayload);
                this.requestMetadata();
            }

            // Expire cached NBT
            this.tickCache(now);

            // 5 queries / server tick
            for (int i = 0; i < Configs.Generic.SERVER_NBT_REQUEST_RATE.getIntegerValue(); i++)
            {
                if (!this.pendingBlockEntitiesQueue.isEmpty())
                {
                    var iter = this.pendingBlockEntitiesQueue.iterator();
                    BlockPos pos = iter.next();
                    iter.remove();

                    if (this.hasServuxServer())
                    {
                        requestServuxBlockEntityData(pos);
                    }
                    else if (this.shouldUseQuery())
                    {
                        // Only check once if we have OP
                        requestQueryBlockEntity(pos);
                    }
                }
                if (!this.pendingEntitiesQueue.isEmpty())
                {
                    var iter = this.pendingEntitiesQueue.iterator();
                    int entityId = iter.next();
                    iter.remove();
                    if (this.hasServuxServer())
                    {
                        requestServuxEntityData(entityId);
                    }
                    else if (this.shouldUseQuery())
                    {
                        // Only check once if we have OP
                        requestQueryEntityData(entityId);
                    }
                }
            }
            this.serverTickTime = System.currentTimeMillis();
        }
    }

    public Identifier getNetworkChannel()
    {
        return ServuxLitematicaHandler.CHANNEL_ID;
    }

    private ClientPlayNetworkHandler getVanillaHandler()
    {
        if (this.mc.player != null)
        {
            return this.mc.player.networkHandler;
        }

        return null;
    }

    public IPluginClientPlayHandler<ServuxLitematicaPacket.Payload> getNetworkHandler()
    {
        return HANDLER;
    }

    @Override
    public void reset(boolean isLogout)
    {
        if (isLogout)
        {
            Litematica.debugLog("EntitiesDataStorage#reset() - log-out");
            HANDLER.reset(this.getNetworkChannel());
            HANDLER.resetFailures(this.getNetworkChannel());
            this.servuxServer = false;
            this.hasInValidServux = false;
            this.sentBackupPackets = false;
            this.receivedBackupPackets = false;
            this.checkOpStatus = false;
            this.hasOpStatus = false;
            this.lastOpCheck = 0L;
        }
        else
        {
            Litematica.debugLog("EntitiesDataStorage#reset() - dimension change or log-in");
            long now = System.currentTimeMillis();
            this.serverTickTime = now - (this.getCacheTimeout() + 5000L);
            this.tickCache(now);
            this.serverTickTime = now;
            this.clientWorld = mc.world;
            this.checkOpStatus = true;
            this.lastOpCheck = now;
        }

        // Clear data
        this.blockEntityCache.clear();
        this.entityCache.clear();
        this.pendingBlockEntitiesQueue.clear();
        this.pendingEntitiesQueue.clear();

        // Litematic Save values
        this.completedChunks.clear();
        this.pendingChunks.clear();
        this.pendingChunkTimeout.clear();
        this.pendingBackupChunk_BlockEntities.clear();
        this.pendingBackupChunk_Entities.clear();
    }

    private boolean shouldUseQuery()
    {
        if (this.hasOpStatus)
        {
//            System.out.printf("shouldUseQuery: HAS OP\n");
            return true;
        }

        if (this.checkOpStatus)
        {
            // Check for 15 minutes after login, or changing dimensions
            if ((System.currentTimeMillis() - this.lastOpCheck) < 900000L)
            {
//                System.out.printf("shouldUseQuery: CHECK OP\n");
                return true;
            }

            this.checkOpStatus = false;
        }

//        System.out.printf("shouldUseQuery: NOT-OP\n");
        return false;
    }

    public void resetOpCheck()
    {
        this.hasOpStatus = false;
        this.checkOpStatus = true;
        this.lastOpCheck = System.currentTimeMillis();
    }

    private long getCacheTimeout()
    {
        return (long) (MathHelper.clamp(Configs.Generic.ENTITY_DATA_SYNC_CACHE_TIMEOUT.getFloatValue(), 0.25f, 30.0f) * 1000L);
    }

    private long getCacheTimeoutLong()
    {
        return (long) (MathHelper.clamp((Configs.Generic.ENTITY_DATA_SYNC_CACHE_TIMEOUT.getFloatValue() * this.longCacheTimeout), 120.0f, 300.0f) * 1000L);
    }

    private void tickCache(long nowTime)
    {
        long blockTimeout = this.getCacheTimeout();
        long entityTimeout = this.getCacheTimeout();
        int count;
        boolean beEmpty = false;
        boolean entEmpty = false;

        // Use LongTimeouts when saving a Litematic Selection,
        // which is pretty much the standard value x 30 (min 120, max 300 seconds)
        if (this.shouldUseLongTimeout)
        {
            blockTimeout = this.getCacheTimeoutLong();
            entityTimeout = this.getCacheTimeoutLong();

            // Add extra time if using QueryNbt only
            if (this.hasServuxServer() == false && this.getIfReceivedBackupPackets())
            {
                blockTimeout += 3000L;
                entityTimeout += 3000L;
            }
        }

        synchronized (this.blockEntityCache)
        {
            count = 0;

            for (BlockPos pos : this.blockEntityCache.keySet())
            {
                Pair<Long, Pair<BlockEntity, NbtCompound>> pair = this.blockEntityCache.get(pos);

                if (nowTime - pair.getLeft() > blockTimeout || pair.getLeft() > nowTime)
                {
//                    Litematica.debugLog("litematicEntityCache: be at pos [{}] has timed out by [{}] ms", pos.toShortString(), blockTimeout);
                    this.blockEntityCache.remove(pos);
                }
                else
                {
                    count++;
                }
            }

            if (count == 0)
            {
                beEmpty = true;
            }
        }

        synchronized (this.entityCache)
        {
            count = 0;

            for (Integer entityId : this.entityCache.keySet())
            {
                Pair<Long, Pair<Entity, NbtCompound>> pair = this.entityCache.get(entityId);

                if (nowTime - pair.getLeft() > entityTimeout || pair.getLeft() > nowTime)
                {
//                    Litematica.debugLog("litematicEntityCache: entity Id [{}] has timed out by [{}] ms", entityId, entityTimeout);
                    this.entityCache.remove(entityId);
                }
                else
                {
                    count++;
                }
            }

            if (count == 0)
            {
                entEmpty = true;
            }
        }

        // End Long timeout phase
        if (beEmpty && entEmpty && this.shouldUseLongTimeout)
        {
            this.shouldUseLongTimeout = false;
        }
    }

    @Override
    public @Nullable NbtCompound getFromBlockEntityCacheNbt(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getRight();
        }

        return null;
    }

    @Override
    public @Nullable BlockEntity getFromBlockEntityCache(BlockPos pos)
    {
        if (this.blockEntityCache.containsKey(pos))
        {
            return this.blockEntityCache.get(pos).getRight().getLeft();
        }

        return null;
    }

    @Override
    public @Nullable NbtCompound getFromEntityCacheNbt(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getRight();
        }

        return null;
    }

    @Override
    public @Nullable Entity getFromEntityCache(int entityId)
    {
        if (this.entityCache.containsKey(entityId))
        {
            return this.entityCache.get(entityId).getRight().getLeft();
        }

        return null;
    }

    public void setIsServuxServer()
    {
        this.servuxServer = true;
        this.hasInValidServux = false;
    }

    public boolean hasServuxServer()
    {
        return this.servuxServer;
    }

    public void setServuxVersion(String ver)
    {
        if (ver != null && ver.isEmpty() == false)
        {
            this.servuxVersion = ver;
            Litematica.debugLog("LitematicDataChannel: joining Servux version {}", ver);
        }
        else
        {
            this.servuxVersion = "unknown";
        }
    }

    public String getServuxVersion()
    {
        return this.servuxVersion;
    }

    public int getPendingBlockEntitiesCount()
    {
        return this.pendingBlockEntitiesQueue.size();
    }

    public int getPendingEntitiesCount()
    {
        return this.pendingEntitiesQueue.size();
    }

    public int getBlockEntityCacheCount()
    {
        return this.blockEntityCache.size();
    }

    public int getEntityCacheCount()
    {
        return this.entityCache.size();
    }

    public boolean getIfReceivedBackupPackets()
    {
        if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue())
        {
            return this.sentBackupPackets & this.receivedBackupPackets;
        }

        return false;
    }

    @Override
    public void onGameInit()
    {
        ClientPlayHandler.getInstance().registerClientPlayHandler(HANDLER);
        HANDLER.registerPlayPayload(ServuxLitematicaPacket.Payload.ID, ServuxLitematicaPacket.Payload.CODEC, IPluginClientPlayHandler.BOTH_CLIENT);
    }

    @Override
    public void onWorldPre()
    {
        if (DataManager.getInstance().hasIntegratedServer() == false)
        {
            HANDLER.registerPlayReceiver(ServuxLitematicaPacket.Payload.ID, HANDLER::receivePlayPayload);
        }
    }

    @Override
    public void onWorldJoin()
    {
        EntityUtils.initEntityUtils();
        // NO-OP
    }

    public void requestMetadata()
    {
        if (DataManager.getInstance().hasIntegratedServer() == false &&
            Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            NbtCompound nbt = new NbtCompound();
            nbt.putString("version", Reference.MOD_STRING);

            HANDLER.encodeClientData(ServuxLitematicaPacket.MetadataRequest(nbt));
        }
    }

    public boolean receiveServuxMetadata(NbtCompound data)
    {
        if (DataManager.getInstance().hasIntegratedServer() == false)
        {
            Litematica.debugLog("LitematicDataChannel: received METADATA from Servux");

            if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
            {
                if (data.getInt("version", -1) != ServuxLitematicaPacket.PROTOCOL_VERSION)
                {
                    Litematica.LOGGER.warn("LitematicDataChannel: Mis-matched protocol version!");
                }

                this.setServuxVersion(data.getString("servux", "?"));
                this.setIsServuxServer();

                return true;
            }
        }

        return false;
    }

    public void onPacketFailure()
    {
        this.servuxServer = false;
        this.hasInValidServux = true;
    }

    @Override
    public @Nullable Pair<BlockEntity, NbtCompound> requestBlockEntity(World world, BlockPos pos)
    {
        // Don't cache/request a BE for the Schematic World
        if (world instanceof WorldSchematic)
        {
            return this.refreshBlockEntityFromWorld(world, pos);
        }
        if (this.blockEntityCache.containsKey(pos))
        {
            // Refresh at 25%
            if (!DataManager.getInstance().hasIntegratedServer() &&
                (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
                 Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue()))
            {
                if (System.currentTimeMillis() - this.blockEntityCache.get(pos).getLeft() > (this.getCacheTimeout() / 4))
                {
//                    Litematica.debugLog("requestBlockEntity: be at pos [{}] requeue at [{}] ms", pos.toShortString(), this.getCacheTimeout() / 4);
                    this.pendingBlockEntitiesQueue.add(pos);
                }
            }

            if (world instanceof ServerWorld)
            {
                return this.refreshBlockEntityFromWorld(world, pos);
            }

            return this.blockEntityCache.get(pos).getRight();
        }
        else if (world.getBlockState(pos).getBlock() instanceof BlockEntityProvider)
        {
            if (DataManager.getInstance().hasIntegratedServer() == false &&
                (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
                 Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue()))
            {
//                Litematica.debugLog("requestBlockEntity: be at pos [{}] queue at [{}] ms", pos.toShortString(), this.getCacheTimeout() / 4);
                this.pendingBlockEntitiesQueue.add(pos);
            }

            return this.refreshBlockEntityFromWorld(world, pos);
        }

        return null;
    }

    private @Nullable Pair<BlockEntity, NbtCompound> refreshBlockEntityFromWorld(World world, BlockPos pos)
    {
        if (world != null && world.getBlockState(pos).hasBlockEntity())
        {
            BlockEntity be = world.getWorldChunk(pos).getBlockEntity(pos);

            if (be != null)
            {
                NbtCompound nbt = be.createNbtWithIdentifyingData(world.getRegistryManager());
                Pair<BlockEntity, NbtCompound> pair = Pair.of(be, nbt);

                if (!(world instanceof WorldSchematic))
                {
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), pair));
                    }
                }

                return pair;
            }
        }

        return null;
    }

    @Override
    public @Nullable Pair<Entity, NbtCompound> requestEntity(World world, int entityId)
    {
        // Don't cache/request for the Schematic World
        if (world instanceof WorldSchematic)
        {
            return this.refreshEntityFromWorld(world, entityId);
        }
        if (this.entityCache.containsKey(entityId))
        {
            // Refresh at 25%
            if (!DataManager.getInstance().hasIntegratedServer() &&
                (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
                 Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue()))
            {
                if (System.currentTimeMillis() - this.entityCache.get(entityId).getLeft() > (this.getCacheTimeout() / 4))
                {
                    //Litematica.debugLog("requestEntity: entity Id [{}] requeue at [{}] ms", entityId, this.getCacheTimeout() / 4);
                    this.pendingEntitiesQueue.add(entityId);
                }
            }

            // Refresh from Server World
            if (world instanceof ServerWorld)
            {
                return this.refreshEntityFromWorld(world, entityId);
            }

            return this.entityCache.get(entityId).getRight();
        }
        if (DataManager.getInstance().hasIntegratedServer() == false &&
            (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
             Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue()))
        {
            this.pendingEntitiesQueue.add(entityId);
        }

        return this.refreshEntityFromWorld(world, entityId);
    }

    private @Nullable Pair<Entity, NbtCompound> refreshEntityFromWorld(World world, int entityId)
    {
        if (world != null)
        {
            Entity entity = world.getEntityById(entityId);

            if (entity != null)
            {
                NbtView view = NbtView.getWriter(world.getRegistryManager());
                entity.writeData(view.getWriter());
                NbtCompound nbt = view.readNbt();
                Identifier id = EntityType.getId(entity.getType());

                if (nbt != null && id != null)
                {
                    nbt.putString("id", id.toString());
                    Pair<Entity, NbtCompound> pair = Pair.of(entity, nbt.copy());

                    if (!(world instanceof WorldSchematic))
                    {
                        synchronized (this.entityCache)
                        {
                            this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), pair));
                        }
                    }

                    return pair;
                }
            }
        }

        return null;
    }

    @Override
    @Nullable
    public Inventory getBlockInventory(World world, BlockPos pos, boolean useNbt)
    {
        if (world instanceof WorldSchematic)
        {
            return InventoryUtils.getInventory(world, pos);
        }
        if (this.blockEntityCache.containsKey(pos))
        {
            Inventory inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getNbtInventory(this.blockEntityCache.get(pos).getRight().getRight(), -1, world.getRegistryManager());
            }
            else
            {
                BlockEntity be = this.blockEntityCache.get(pos).getRight().getLeft();
                BlockState state = world.getBlockState(pos);

                if (state.isIn(BlockTags.AIR) || !state.hasBlockEntity())
                {
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.remove(pos);
                    }

                    // Don't keep requesting if we're tick warping or something.
                    return null;
                }

                if (be instanceof Inventory inv1)
                {
                    if (be instanceof ChestBlockEntity && state.contains(ChestBlock.CHEST_TYPE))
                    {
                        ChestType type = state.get(ChestBlock.CHEST_TYPE);

                        if (type != ChestType.SINGLE)
                        {
                            BlockPos posAdj = pos.offset(ChestBlock.getFacing(state));
                            if (!world.isChunkLoaded(posAdj)) return null;
                            BlockState stateAdj = world.getBlockState(posAdj);

                            var dataAdj = this.getFromBlockEntityCache(posAdj);

                            if (dataAdj == null)
                            {
                                this.requestBlockEntity(world, posAdj);
                            }

                            if (stateAdj.getBlock() == state.getBlock() &&
                                dataAdj instanceof ChestBlockEntity inv2 &&
                                stateAdj.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE &&
                                stateAdj.get(ChestBlock.FACING) == state.get(ChestBlock.FACING))
                            {
                                Inventory invRight = type == ChestType.RIGHT ? inv1 : inv2;
                                Inventory invLeft = type == ChestType.RIGHT ? inv2 : inv1;

                                inv = new DoubleInventory(invRight, invLeft);
                            }
                        }
                        else
                        {
                            inv = inv1;
                        }
                    }
                    else
                    {
                        inv = inv1;
                    }
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
            Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue())
        {
            this.requestBlockEntity(world, pos);
        }

        return null;
    }

    @Override
    @Nullable
    public Inventory getEntityInventory(World world, int entityId, boolean useNbt)
    {
        if (world instanceof WorldSchematic)
        {
            return null;
        }

        if (this.entityCache.containsKey(entityId) && this.getWorld() != null)
        {
            Inventory inv = null;

            if (useNbt)
            {
                inv = InventoryUtils.getNbtInventory(this.entityCache.get(entityId).getRight().getRight(), -1, this.getWorld().getRegistryManager());
            }
            else
            {
                Entity entity = this.entityCache.get(entityId).getRight().getLeft();

                if (entity instanceof Inventory)
                {
                    inv = (Inventory) entity;
                }
                else if (entity instanceof PlayerEntity player && player != null)
                {
                    inv = new SimpleInventory(player.getInventory().getMainStacks().toArray(new ItemStack[36]));
                }
                else if (entity instanceof VillagerEntity)
                {
                    inv = ((VillagerEntity) entity).getInventory();
                }
                else if (entity instanceof AbstractHorseEntity)
                {
                    inv = ((IMixinAbstractHorseEntity) entity).malilib_getHorseInventory();
                }
                else if (entity instanceof PiglinEntity)
                {
                    inv = ((IMixinPiglinEntity) entity).malilib_getInventory();
                }
            }

            if (inv != null)
            {
                return inv;
            }
        }

        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue() ||
            Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue())
        {
            this.requestEntity(world, entityId);
        }

        return null;
    }

    private void requestQueryBlockEntity(BlockPos pos)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue() == false)
        {
            return;
        }

        ClientPlayNetworkHandler handler = this.getVanillaHandler();

        if (handler != null)
        {
            this.sentBackupPackets = true;
            handler.getDataQueryHandler().queryBlockNbt(pos, nbtCompound ->
            {
                handleBlockEntityData(pos, nbtCompound, null);
            });
            this.transactionToBlockPosOrEntityId.put(((IMixinDataQueryHandler) handler.getDataQueryHandler()).malilib_currentTransactionId(), Either.left(pos));
        }
    }

    private void requestQueryEntityData(int entityId)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC_BACKUP.getBooleanValue() == false)
        {
            return;
        }

        ClientPlayNetworkHandler handler = this.getVanillaHandler();

        if (handler != null)
        {
            this.sentBackupPackets = true;
            handler.getDataQueryHandler().queryEntityNbt(entityId, nbtCompound ->
            {
                handleEntityData(entityId, nbtCompound);
            });
            this.transactionToBlockPosOrEntityId.put(((IMixinDataQueryHandler) handler.getDataQueryHandler()).malilib_currentTransactionId(), Either.right(entityId));
        }
    }

    private void requestServuxBlockEntityData(BlockPos pos)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            HANDLER.encodeClientData(ServuxLitematicaPacket.BlockEntityRequest(pos));
        }
    }

    private void requestServuxEntityData(int entityId)
    {
        if (Configs.Generic.ENTITY_DATA_SYNC.getBooleanValue())
        {
            HANDLER.encodeClientData(ServuxLitematicaPacket.EntityRequest(entityId));
        }
    }

    // The minY, maxY should be calculated based on the Selection Box...  But for now, we can just grab the entire chunk.
    public void requestServuxBulkEntityData(ChunkPos chunkPos, int minY, int maxY)
    {
        if (this.hasServuxServer() == false)
        {
            return;
        }

        NbtCompound req = new NbtCompound();

        this.completedChunks.remove(chunkPos);
        this.pendingChunks.add(chunkPos);
        this.pendingChunkTimeout.put(chunkPos, Util.getMeasuringTimeMs());

        minY = MathHelper.clamp(minY, -60, 319);
        maxY = MathHelper.clamp(maxY, -60, 319);

        req.putString("Task", "BulkEntityRequest");
        req.putInt("minY", minY);
        req.putInt("maxY", maxY);

        Litematica.debugLog("EntitiesDataStorage#requestServuxBulkEntityData(): for chunkPos [{}] to Servux (minY [{}], maxY [{}])", chunkPos.toString(), minY, maxY);
        HANDLER.encodeClientData(ServuxLitematicaPacket.BulkNbtRequest(chunkPos, req));
    }

    public void requestBackupBulkEntityData(ChunkPos chunkPos, int minY, int maxY)
    {
        if (this.getIfReceivedBackupPackets() == false || this.hasServuxServer())
        {
            return;
        }

        this.completedChunks.remove(chunkPos);
        minY = MathHelper.clamp(minY, -60, 319);
        maxY = MathHelper.clamp(maxY, -60, 319);

        ClientWorld world = this.getClientWorld();
        Chunk chunk = world != null ? world.getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false) : null;

        if (chunk == null)
        {
            return;
        }

        BlockPos pos1 = new BlockPos(chunkPos.getStartX(), minY, chunkPos.getStartZ());
        BlockPos pos2 = new BlockPos(chunkPos.getEndX(),   maxY, chunkPos.getEndZ());
        Box bb = PositionUtils.createEnclosingAABB(pos1, pos2);
        Set<BlockPos> teSet = chunk.getBlockEntityPositions();
        List<Entity> entList = world.getOtherEntities(null, bb, EntityUtils.NOT_PLAYER);

        Litematica.debugLog("EntitiesDataStorage#requestBackupBulkEntityData(): for chunkPos {} (minY [{}], maxY [{}]) // Request --> TE: [{}], E: [{}]", chunkPos.toString(), minY, maxY, teSet.size(), entList.size());
        //System.out.printf("0: ChunkPos [%s], Box [%s] // teSet [%d], entList [%d]\n", chunkPos.toString(), bb.toString(), teSet.size(), entList.size());

        for (BlockPos tePos : teSet)
        {
            if ((tePos.getX() < chunkPos.getStartX() || tePos.getX() > chunkPos.getEndX()) ||
                (tePos.getZ() < chunkPos.getStartZ() || tePos.getZ() > chunkPos.getEndZ()) ||
                (tePos.getY() < minY || tePos.getY() > maxY))
            {
                continue;
            }

            this.requestBlockEntity(world, tePos);
        }

        if (teSet.size() > 0)
        {
            this.pendingBackupChunk_BlockEntities.put(chunkPos, teSet);
        }

        Set<Integer> entSet = new LinkedHashSet<>();

        for (Entity entity : entList)
        {
            this.requestEntity(world, entity.getId());
            entSet.add(entity.getId());
        }

        if (entSet.size() > 0)
        {
            this.pendingBackupChunk_Entities.put(chunkPos, entSet);
        }

        if (teSet.size() > 0 || entSet.size() > 0)
        {
            this.pendingChunks.add(chunkPos);
            this.pendingChunkTimeout.put(chunkPos, Util.getMeasuringTimeMs());
        }
        else
        {
            this.completedChunks.add(chunkPos);
        }
    }

    private boolean markBackupBlockEntityComplete(ChunkPos chunkPos, BlockPos pos)
    {
        if (this.getIfReceivedBackupPackets() == false || this.hasServuxServer())
        {
            return true;
        }

        //Litematica.debugLog("EntitiesDataStorage#markBackupBlockEntityComplete() - Marking ChunkPos {} - Block Entity at [{}] as complete.", chunkPos.toString(), pos.toShortString());

        if (this.pendingChunks.contains(chunkPos))
        {
            if (this.pendingBackupChunk_BlockEntities.containsKey(chunkPos))
            {
                Set<BlockPos> teSet = this.pendingBackupChunk_BlockEntities.get(chunkPos);

                if (teSet.contains(pos))
                {
                    teSet.remove(pos);

                    if (teSet.isEmpty())
                    {
                        Litematica.debugLog("EntitiesDataStorage#markBackupBlockEntityComplete(): ChunkPos {} - Block Entity List Complete!", chunkPos.toString());
                        this.pendingBackupChunk_BlockEntities.remove(chunkPos);
                        this.pendingChunks.remove(chunkPos);
                        this.pendingChunkTimeout.remove(chunkPos);
                        this.completedChunks.add(chunkPos);
                        return true;
                    }
                    else
                    {
                        this.pendingBackupChunk_BlockEntities.replace(chunkPos, teSet);
                    }
                }
            }
        }

        return false;
    }

    private boolean markBackupEntityComplete(ChunkPos chunkPos, int entityId)
    {
        if (this.getIfReceivedBackupPackets() == false || this.hasServuxServer())
        {
            return true;
        }

        //Litematica.debugLog("EntitiesDataStorage#markBackupEntityComplete() - Marking ChunkPos {} - EntityId [{}] as complete.", chunkPos.toString(), entityId);

        if (this.pendingChunks.contains(chunkPos))
        {
            if (this.pendingBackupChunk_Entities.containsKey(chunkPos))
            {
                Set<Integer> entSet = this.pendingBackupChunk_Entities.get(chunkPos);

                if (entSet.contains(entityId))
                {
                    entSet.remove(entityId);

                    if (entSet.isEmpty())
                    {
                        Litematica.debugLog("EntitiesDataStorage#markBackupEntityComplete(): ChunkPos {} - EntitiyList Complete!", chunkPos.toString());
                        this.pendingBackupChunk_Entities.remove(chunkPos);
                        this.pendingChunks.remove(chunkPos);
                        this.pendingChunkTimeout.remove(chunkPos);
                        this.completedChunks.add(chunkPos);
                        return true;
                    }
                    else
                    {
                        this.pendingBackupChunk_Entities.replace(chunkPos, entSet);
                    }
                }
            }
        }

        return false;
    }

    @Override
    @Nullable
    public BlockEntity handleBlockEntityData(BlockPos pos, NbtCompound nbt, @Nullable Identifier type)
    {
        this.pendingBlockEntitiesQueue.remove(pos);
        if (nbt == null || this.getClientWorld() == null) return null;

        BlockEntity blockEntity = this.getClientWorld().getBlockEntity(pos);

        if (blockEntity != null && (type == null || type.equals(BlockEntityType.getId(blockEntity.getType()))))
        {
            if (nbt.contains(NbtKeys.ID) == false)
            {
                Identifier id = BlockEntityType.getId(blockEntity.getType());

                if (id != null)
                {
                    nbt.putString(NbtKeys.ID, id.toString());
                }
            }

            synchronized (this.blockEntityCache)
            {
                this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity, nbt)));
            }

            NbtView view = NbtView.getReader(nbt, this.getClientWorld().getRegistryManager());
            blockEntity.read(view.getReader());
            ChunkPos chunkPos = new ChunkPos(pos);

            if (this.hasPendingChunk(chunkPos) && this.hasServuxServer() == false)
            {
                this.markBackupBlockEntityComplete(chunkPos, pos);
            }

            return blockEntity;
        }

        Optional<RegistryEntry.Reference<BlockEntityType<?>>> opt = Registries.BLOCK_ENTITY_TYPE.getEntry(type);

        if (opt.isPresent())
        {
            BlockEntityType<?> beType = opt.get().value();

            if (beType.supports(this.getClientWorld().getBlockState(pos)))
            {
                BlockEntity blockEntity2 = beType.instantiate(pos, this.getClientWorld().getBlockState(pos));

                if (blockEntity2 != null)
                {
                    if (nbt.contains(NbtKeys.ID) == false)
                    {
                        Identifier id = BlockEntityType.getId(beType);

                        if (id != null)
                        {
                            nbt.putString(NbtKeys.ID, id.toString());
                        }
                    }
                    synchronized (this.blockEntityCache)
                    {
                        this.blockEntityCache.put(pos, Pair.of(System.currentTimeMillis(), Pair.of(blockEntity2, nbt)));
                    }

                    if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
                    {
                        NbtView view = NbtView.getReader(nbt, this.getClientWorld().getRegistryManager());
                        blockEntity2.read(view.getReader());
                        this.getClientWorld().addBlockEntity(blockEntity2);
                    }

                    ChunkPos chunkPos = new ChunkPos(pos);

                    if (this.hasPendingChunk(chunkPos) && this.hasServuxServer() == false)
                    {
                        this.markBackupBlockEntityComplete(chunkPos, pos);
                    }

                    return blockEntity2;
                }
            }
        }

        return null;
    }

    @Override
    @Nullable
    public Entity handleEntityData(int entityId, NbtCompound nbt)
    {
        this.pendingEntitiesQueue.remove(entityId);
        if (nbt == null || this.getClientWorld() == null) return null;
        Entity entity = this.getClientWorld().getEntityById(entityId);

        if (entity != null)
        {
            if (nbt.contains(NbtKeys.ID) == false)
            {
                Identifier id = EntityType.getId(entity.getType());

                if (id != null)
                {
                    nbt.putString(NbtKeys.ID, id.toString());
                }
            }
            synchronized (this.entityCache)
            {
                this.entityCache.put(entityId, Pair.of(System.currentTimeMillis(), Pair.of(entity, nbt)));
            }

            if (Configs.Generic.ENTITY_DATA_LOAD_NBT.getBooleanValue())
            {
                EntityUtils.loadNbtIntoEntity(entity, nbt);
            }

            if (this.hasPendingChunk(entity.getChunkPos()) && this.hasServuxServer() == false)
            {
                this.markBackupEntityComplete(entity.getChunkPos(), entityId);
            }
        }

        return entity;
    }

    @Override
    public void handleBulkEntityData(int transactionId, @Nullable NbtCompound nbt)
    {
        if (nbt == null)
        {
            return;
        }

        String task = nbt.getString("Task", "BulkEntityReply");

        // TODO --> Split out the task this way (I should have done this under sakura.12, etc),
        //  So we need to check if the "Task" is not included for now... (Wait for the updates to bake in)
        if (task.equals("BulkEntityReply"))
        {
            NbtList tileList = nbt.contains("TileEntities") ? nbt.getListOrEmpty("TileEntities") : new NbtList();
            NbtList entityList = nbt.contains("Entities") ? nbt.getListOrEmpty("Entities") : new NbtList();
            ChunkPos chunkPos = new ChunkPos(nbt.getInt("chunkX", 0), nbt.getInt("chunkZ", 0));

            this.shouldUseLongTimeout = true;

            for (int i = 0; i < tileList.size(); ++i)
            {
                NbtCompound te = tileList.getCompoundOrEmpty(i);
                BlockPos pos = NbtUtils.readBlockPos(te);
                Identifier type = Identifier.of(te.getString("id", ""));

                this.handleBlockEntityData(pos, te, type);
            }

            for (int i = 0; i < entityList.size(); ++i)
            {
                NbtCompound ent = entityList.getCompoundOrEmpty(i);
                Vec3d pos = NbtUtils.readEntityPositionFromTag(ent);
                int entityId = ent.getInt("entityId", 0);

                this.handleEntityData(entityId, ent);
            }

            this.pendingChunks.remove(chunkPos);
            this.pendingChunkTimeout.remove(chunkPos);
            this.completedChunks.add(chunkPos);

            Litematica.debugLog("EntitiesDataStorage#handleBulkEntityData(): [ChunkPos {}] received TE: [{}], and E: [{}] entiries from Servux", chunkPos.toString(), tileList.size(), entityList.size());
        }
    }

    @Override
    public void handleVanillaQueryNbt(int transactionId, NbtCompound nbt)
    {
        if (this.checkOpStatus)
        {
            this.hasOpStatus = true;
            this.checkOpStatus = false;
            this.lastOpCheck = System.currentTimeMillis();
        }

        Either<BlockPos, Integer> either = this.transactionToBlockPosOrEntityId.remove(transactionId);

        if (either != null)
        {
            this.receivedBackupPackets = true;
            either.ifLeft(pos ->     handleBlockEntityData(pos, nbt, null))
                  .ifRight(entityId -> handleEntityData(entityId, nbt));
        }
    }

    public boolean hasPendingChunk(ChunkPos pos)
    {
        if (this.hasServuxServer() || this.getIfReceivedBackupPackets())
        {
            return this.pendingChunks.contains(pos);
        }

        return false;
    }

    private void checkForPendingChunkTimeout(ChunkPos pos)
    {
        if ((this.hasServuxServer() && this.hasPendingChunk(pos)) ||
            (this.getIfReceivedBackupPackets() && this.hasPendingChunk(pos)))
        {
            long now = Util.getMeasuringTimeMs();

            // Take no action when ChunkPos is not loaded by the ClientWorld.
            if (WorldUtils.isClientChunkLoaded(mc.world, pos.x, pos.z) == false)
            {
                this.pendingChunkTimeout.replace(pos, now);
                return;
            }

            long duration = now - this.pendingChunkTimeout.get(pos);

            if (duration > (this.getChunkTimeoutMs()))
            {
                Litematica.debugLog("EntitiesDataStorage#checkForPendingChunkTimeout(): [ChunkPos {}] has timed out waiting for data, marking complete without Receiving Entity Data.", pos.toString());
                this.pendingChunkTimeout.remove(pos);
                this.pendingChunks.remove(pos);
                this.completedChunks.add(pos);
            }
        }
    }

    private long getChunkTimeoutMs()
    {
        if (this.hasServuxServer())
        {
            return this.chunkTimeoutMs;
        }
        else if (this.getIfReceivedBackupPackets())
        {
            return this.chunkTimeoutMs + 3000L;
        }

        return 1000L;
    }

    public boolean hasCompletedChunk(ChunkPos pos)
    {
        if (this.hasServuxServer() || this.getIfReceivedBackupPackets())
        {
            this.checkForPendingChunkTimeout(pos);
            return this.completedChunks.contains(pos);
        }

        return true;
    }

    public void markCompletedChunkDirty(ChunkPos pos)
    {
        if (this.hasServuxServer() || this.getIfReceivedBackupPackets())
        {
            this.completedChunks.remove(pos);
        }
    }

    // TODO --> Only in case we need to save config settings in the future
    public JsonObject toJson()
    {
        return new JsonObject();
    }

    public void fromJson(JsonObject obj)
    {
        // NO-OP
    }
}
