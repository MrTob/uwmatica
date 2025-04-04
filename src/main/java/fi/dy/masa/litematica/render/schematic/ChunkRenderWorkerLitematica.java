package fi.dy.masa.litematica.render.schematic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import javax.annotation.Nullable;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.crash.CrashReport;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;

import fi.dy.masa.litematica.Litematica;

public class ChunkRenderWorkerLitematica implements Runnable
{
    private static final Logger LOGGER = Litematica.LOGGER;

    private final ChunkRenderDispatcherLitematica chunkRenderDispatcher;
    final private BufferAllocatorCache allocatorCache;
    private boolean shouldRun;
    private Profiler profiler;

    public ChunkRenderWorkerLitematica(ChunkRenderDispatcherLitematica chunkRenderDispatcherIn, Profiler profiler)
    {
        this(chunkRenderDispatcherIn, null, profiler);
    }

    public ChunkRenderWorkerLitematica(ChunkRenderDispatcherLitematica chunkRenderDispatcherIn, @Nullable BufferAllocatorCache allocatorCache, Profiler profiler)
    {
        this.shouldRun = true;
        this.chunkRenderDispatcher = chunkRenderDispatcherIn;
        this.allocatorCache = allocatorCache;
        this.profiler = profiler;

//        LOGGER.error("[LW] init() [Cache: {}]", allocatorCache != null);
    }

    @Override
    public void run()
    {
//        LOGGER.warn("[LW] run()");

        if (this.profiler == null)
        {
            this.profiler = Profilers.get();
        }

        while (this.shouldRun)
        {
            try
            {
                this.processTask(this.chunkRenderDispatcher.getNextChunkUpdate(), this.profiler);
            }
            catch (InterruptedException e)
            {
                LOGGER.debug("Stopping chunk worker due to interrupt");
                return;
            }
            catch (Throwable throwable)
            {
                CrashReport crashreport = CrashReport.create(throwable, "Batching chunks");
                MinecraftClient.getInstance().setCrashReportSupplier(MinecraftClient.getInstance().addDetailsToCrashReport(crashreport));
                return;
            }
        }
    }

    /* Threaded Code
    protected void processTask(final ChunkRenderTaskSchematic task) throws InterruptedException
    {
        ChunkRenderTaskSchematic.Status oldStatus;
        oldStatus = task.casStatus(ChunkRenderTaskSchematic.Status.PENDING, ChunkRenderTaskSchematic.Status.COMPILING);

        if (oldStatus != ChunkRenderTaskSchematic.Status.PENDING)
        {
            return;
        }

        Entity entity = MinecraftClient.getInstance().getCameraEntity();

        if (entity == null)
        {
            task.finish();
            return;
        }

        if (!task.setRegionRenderCacheBuilder(this.getRegionRenderAllocatorCache()))
        {
            throw new InterruptedException("No free Allocator Cache found");
        }

        ChunkRenderTaskSchematic.Type taskType = task.getType();
        switch (task.getType())
        {
            case REBUILD_CHUNK -> task.getRenderChunk().rebuildChunk(task);
            case RESORT_TRANSPARENCY -> task.getRenderChunk().resortTransparency(task);
        }

        oldStatus = task.casStatus(ChunkRenderTaskSchematic.Status.COMPILING, ChunkRenderTaskSchematic.Status.UPLOADING);
        if (oldStatus != ChunkRenderTaskSchematic.Status.COMPILING)
        {
            resetRenderAllocators(task);
            return;
        }

        final ChunkRenderDataSchematic chunkRenderData = task.getChunkRenderData();
        ArrayList<ListenableFuture<Object>> futuresList = Lists.newArrayList();
        ChunkRendererSchematicVbo renderChunk = task.getRenderChunk();
        BufferAllocatorCache allocators = task.getAllocatorCache();

        switch (taskType)
        {
            case REBUILD_CHUNK ->
            {
                for(RenderLayer layer: ChunkRenderLayers.LAYERS)
                    if (!chunkRenderData.isBlockLayerEmpty(layer))
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(layer, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), false));
                for (OverlayRenderType type : ChunkRenderLayers.TYPES)
                    if (!chunkRenderData.isOverlayTypeEmpty(type))
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(type, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), false));
            }
            case RESORT_TRANSPARENCY ->
            {
                RenderLayer layer = RenderLayer.getTranslucent();
                if (!chunkRenderData.isBlockLayerEmpty(layer))
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(RenderLayer.getTranslucent(), allocators, renderChunk, chunkRenderData, task.getDistanceSq(), true));
                if (!chunkRenderData.isOverlayTypeEmpty(OverlayRenderType.QUAD))
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(OverlayRenderType.QUAD, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), true));
            }
        }

        final ListenableFuture<List<Object>> listenablefuture = Futures.allAsList(futuresList);

        task.addFinishRunnable(() -> listenablefuture.cancel(false));
        Futures.addCallback(listenablefuture, new FutureCallback<List<Object>>()
        {
            @Override
            public void onSuccess(@Nullable List<Object> list)
            {
                ChunkRenderWorkerLitematica.this.clearRenderAllocators(task);
                task.casStatus(ChunkRenderTaskSchematic.Status.UPLOADING,ChunkRenderTaskSchematic.Status.DONE);
                task.getRenderChunk().setChunkRenderData(chunkRenderData);
            }

            @Override
            public void onFailure(@NotNull Throwable throwable)
            {
                ChunkRenderWorkerLitematica.this.resetRenderAllocators(task);
                if (!(throwable instanceof CancellationException) && !(throwable instanceof InterruptedException))
                    MinecraftClient.getInstance().setCrashReportSupplier(CrashReport.create(throwable, "Rendering Litematica chunk"));
            }
        }, MoreExecutors.directExecutor());
    }
     */

    protected void processTask(final ChunkRenderTaskSchematic task, Profiler profiler) throws InterruptedException
    {
        profiler.push("process_task");
        task.getLock().lock();

//        LOGGER.warn("[LW] processTask() task [{}] / [{}]", task.getType().name(), task.getStatus().name());
        try
        {
            if (task.getStatus() != ChunkRenderTaskSchematic.Status.PENDING)
            {
                if (!task.isFinished())
                {
                    LOGGER.warn("Chunk render task was {} when I expected it to be pending; ignoring task", (Object) task.getStatus());
                }

                profiler.pop();
                return;
            }

            task.setStatus(ChunkRenderTaskSchematic.Status.COMPILING);
        }
        finally
        {
            task.getLock().unlock();
        }

        Entity entity = MinecraftClient.getInstance().getCameraEntity();

        if (entity == null)
        {
            task.finish();
        }
        else
        {
            if (!task.setRegionRenderCacheBuilder(this.getRegionRenderAllocatorCache()))
            {
                profiler.pop();
                throw new InterruptedException("No free Allocator Cache found");
            }

            ChunkRenderTaskSchematic.Type taskType = task.getType();

            profiler.swap("run_task_now_" + taskType.name());
            if (taskType == ChunkRenderTaskSchematic.Type.REBUILD_CHUNK)
            {
                //LOGGER.warn("[LW] (REBUILD_CHUNK) --> [VBO]");
                task.getRenderChunk().rebuildChunk(task, profiler);
            }
            else if (taskType == ChunkRenderTaskSchematic.Type.RESORT_TRANSPARENCY)
            {
                //LOGGER.warn("[LW] (RESORT_TRANSPARENCY) --> [VBO]");
                task.getRenderChunk().resortTransparency(task, profiler);
            }

            task.getLock().lock();

            try
            {
                if (task.getStatus() != ChunkRenderTaskSchematic.Status.COMPILING)
                {
                    if (task.isFinished() == false)
                    {
                        LOGGER.warn("Chunk render task was {} when I expected it to be compiling; aborting task", (Object) task.getStatus());
                    }

                    this.resetRenderAllocators(task);
                    profiler.pop();
                    return;
                }

                task.setStatus(ChunkRenderTaskSchematic.Status.UPLOADING);
            }
            finally
            {
                task.getLock().unlock();
            }

            profiler.swap("run_task_schedule_"+ taskType.name());
            final ChunkRenderDataSchematic chunkRenderData = task.getChunkRenderData();
            ArrayList<ListenableFuture<Object>> futuresList = Lists.newArrayList();
            ChunkRendererSchematicVbo renderChunk = task.getRenderChunk();
            BufferAllocatorCache allocators = task.getAllocatorCache();

            if (taskType == ChunkRenderTaskSchematic.Type.REBUILD_CHUNK)
            {
                //LOGGER.warn("[LW] (REBUILD_CHUNK) --> Schedule Uploads");

                //if (GuiBase.isCtrlDown()) System.out.printf("pre uploadChunk()\n");
                for (RenderLayer layer : ChunkRenderLayers.LAYERS)
                {
                    if (chunkRenderData.isBlockLayerEmpty(layer) == false)
                    {
                        //if (GuiBase.isCtrlDown()) System.out.printf("REBUILD_CHUNK pre uploadChunkBlocks()\n");
//                        System.out.printf("REBUILD_CHUNK pre uploadChunkBlocks(%s)\n", ChunkRenderLayers.getFriendlyName(layer));
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(layer, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), false, profiler));
                    }
                }

                for (OverlayRenderType type : ChunkRenderLayers.TYPES)
                {
                    if (chunkRenderData.isOverlayTypeEmpty(type) == false)
                    {
                        //if (GuiBase.isCtrlDown()) System.out.printf("REBUILD_CHUNK pre uploadChunkOverlay()\n");
//                        System.out.printf("REBUILD_CHUNK pre uploadChunkOverlay(%s)\n", type.name());
                        futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(type, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), false, profiler));
                    }
                }
            }
            else if (taskType == ChunkRenderTaskSchematic.Type.RESORT_TRANSPARENCY)
            {
                //LOGGER.warn("[LW] (RESORT_TRANSPARENCY) --> Schedule Uploads");
                RenderLayer layer = RenderLayer.getTranslucent();

                if (chunkRenderData.isBlockLayerEmpty(layer) == false)
                {
                    //System.out.printf("RESORT_TRANSPARENCY pre uploadChunkBlocks(%s)\n", layer.toString());
//                    System.out.printf("REBUILD_CHUNK pre uploadChunkBlocks(%s)\n", ChunkRenderLayers.getFriendlyName(layer));
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkBlocks(RenderLayer.getTranslucent(), allocators, renderChunk, chunkRenderData, task.getDistanceSq(), true, profiler));
                }
                if (chunkRenderData.isOverlayTypeEmpty(OverlayRenderType.QUAD) == false)
                {
                    //if (GuiBase.isCtrlDown()) System.out.printf("RESORT_TRANSPARENCY pre uploadChunkOverlay()\n");
//                    System.out.printf("REBUILD_CHUNK pre uploadChunkOverlay(%s)\n", OverlayRenderType.QUAD.name());
                    futuresList.add(this.chunkRenderDispatcher.uploadChunkOverlay(OverlayRenderType.QUAD, allocators, renderChunk, chunkRenderData, task.getDistanceSq(), true, profiler));
                }
            }

            profiler.swap("run_task_later_" + taskType.name());

            //LOGGER.warn("[LW] (TASK COMBINE) --> futuresList size [{}]", futuresList.size());

            final ListenableFuture<List<Object>> listenablefuture = Futures.allAsList(futuresList);

            task.addFinishRunnable(new Runnable()
            {
                @Override
                public void run()
                {
                    listenablefuture.cancel(false);
                }
            });

            Futures.addCallback(listenablefuture, new FutureCallback<>()
            {
                public void onSuccess(@Nullable List<Object> list)
                {
//                    ChunkRenderWorkerLitematica.this.clearRenderAllocators(task);
                    task.getLock().lock();

                    label49:
                    {
                        try
                        {
                            if (task.getStatus() == ChunkRenderTaskSchematic.Status.UPLOADING)
                            {
                                task.setStatus(ChunkRenderTaskSchematic.Status.DONE);
                                break label49;
                            }

                            if (task.isFinished() == false)
                            {
                                LOGGER.warn("Chunk render task was {} when I expected it to be uploading; aborting task", (Object) task.getStatus());
                            }
                        }
                        finally
                        {
                            task.getLock().unlock();
                        }

                        return;
                    }

                    task.getRenderChunk().setChunkRenderData(chunkRenderData);
                }

                @Override
                public void onFailure(@NotNull Throwable throwable)
                {
                    ChunkRenderWorkerLitematica.this.resetRenderAllocators(task);

                    if ((throwable instanceof CancellationException) == false && (throwable instanceof InterruptedException) == false)
                    {
                        MinecraftClient.getInstance().setCrashReportSupplier(CrashReport.create(throwable, "Rendering Litematica chunk"));
                    }
                }
            }, MoreExecutors.directExecutor());
        }

        profiler.pop();
    }

    @Nullable
    private BufferAllocatorCache getRegionRenderAllocatorCache() throws InterruptedException
    {
        return this.allocatorCache != null ? this.allocatorCache : this.chunkRenderDispatcher.allocateRenderAllocators();
    }

    private void clearRenderAllocators(ChunkRenderTaskSchematic generator)
    {
        BufferAllocatorCache bufferAllocatorCache = generator.getAllocatorCache();

        if (bufferAllocatorCache != null && !bufferAllocatorCache.isClear())
        {
            bufferAllocatorCache.clearAll();
        }

        if (this.allocatorCache == null)
        {
            this.chunkRenderDispatcher.freeRenderAllocators(bufferAllocatorCache);
        }
    }

    private void resetRenderAllocators(ChunkRenderTaskSchematic generator)
    {
        BufferAllocatorCache bufferAllocatorCache = generator.getAllocatorCache();

        if (bufferAllocatorCache != null && !bufferAllocatorCache.isClear())
        {
            bufferAllocatorCache.resetAll();
        }

        if (this.allocatorCache == null)
        {
            this.chunkRenderDispatcher.freeRenderAllocators(bufferAllocatorCache);
        }
    }

    public void notifyToStop()
    {
//        LOGGER.warn("[LW] stop()");
        this.shouldRun = false;
    }
}
