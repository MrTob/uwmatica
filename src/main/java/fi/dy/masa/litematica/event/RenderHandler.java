package fi.dy.masa.litematica.event;

import java.util.function.Supplier;
import org.joml.Matrix4f;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.util.profiler.Profiler;

import fi.dy.masa.malilib.interfaces.IRenderer;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.litematica.Reference;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.config.Hotkeys;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiSchematicManager;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import fi.dy.masa.litematica.render.OverlayRenderer;
import fi.dy.masa.litematica.render.infohud.InfoHud;
import fi.dy.masa.litematica.render.infohud.ToolHud;
import fi.dy.masa.litematica.tool.ToolMode;

public class RenderHandler implements IRenderer
{
    @Override
    public void onRenderWorldPreWeather(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum, Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
//        MinecraftClient mc = MinecraftClient.getInstance();
//
//        if (Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && mc.player != null)
//        {
//        }
    }

    @Override
    public void onRenderWorldLastAdvanced(Framebuffer fb, Matrix4f posMatrix, Matrix4f projMatrix, Frustum frustum, Camera camera, BufferBuilderStorage buffers, Profiler profiler)
    {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && mc.player != null)
        {
            profiler.push("overlay_boxes");
            OverlayRenderer.getInstance().renderBoxes(posMatrix, profiler);

            if (Configs.InfoOverlays.VERIFIER_OVERLAY_ENABLED.getBooleanValue())
            {
                profiler.swap("overlay_mismatches");
                OverlayRenderer.getInstance().renderSchematicVerifierMismatches(posMatrix, profiler, Hotkeys.RENDER_OVERLAY_THROUGH_BLOCKS.getKeybind().isKeybindHeld());
            }

            if (DataManager.getToolMode() == ToolMode.REBUILD)
            {
                profiler.swap("overlay_targeting");
                OverlayRenderer.getInstance().renderSchematicRebuildTargetingOverlay(posMatrix, profiler);
            }

            // Schematic Overlay Rendering
            profiler.swap("schematic_overlay");
            LitematicaRenderer.getInstance().piecewiseRenderOverlay(null, null, profiler);
            profiler.pop();
        }
    }

    @Override
    public Supplier<String> getProfilerSectionSupplier()
    {
        return () -> Reference.MOD_ID+"_render_handler";
    }

    @Override
    public void onRenderGameOverlayPostAdvanced(DrawContext drawContext, float partialTicks, Profiler profiler, MinecraftClient mc)
    {
        if (Configs.Visuals.ENABLE_RENDERING.getBooleanValue() && mc.player != null)
        {
            profiler.push("overlay_hud");
            // The Info HUD renderers can decide if they want to be rendered in GUIs
            InfoHud.getInstance().renderHud(drawContext);

            if (GuiUtils.getCurrentScreen() == null)
            {
                if (mc.options.hudHidden == false)
                {
                    ToolHud.getInstance().renderHud(drawContext);
                    profiler.swap("overlay_hover_info");
                    OverlayRenderer.getInstance().renderHoverInfo(drawContext, mc, profiler);
                }

                if (GuiSchematicManager.hasPendingPreviewTask())
                {
                    profiler.swap("overlay_preview_frame");
                    OverlayRenderer.getInstance().renderPreviewFrame(drawContext, mc, profiler);
                }
            }

            profiler.pop();
        }
    }
}
