package fi.dy.masa.litematica.gui.widgets;

import java.util.List;
import javax.annotation.Nullable;
import org.joml.Matrix3x2fStack;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.RenderUtils;
import fi.dy.masa.malilib.util.GuiUtils;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.malilib.util.game.BlockUtils;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier.BlockMismatchEntry;
import fi.dy.masa.litematica.gui.Icons;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.SortCriteria;
import fi.dy.masa.litematica.util.ItemUtils;

public class WidgetSchematicVerificationResult extends WidgetListEntrySortable<BlockMismatchEntry>
{
//    private static final LocalRandom RAND = new LocalRandom(0);

    public static final String HEADER_EXPECTED = "litematica.gui.label.schematic_verifier.expected";
    public static final String HEADER_FOUND = "litematica.gui.label.schematic_verifier.found";
    public static final String HEADER_COUNT = "litematica.gui.label.schematic_verifier.count";

    private static int maxNameLengthExpected;
    private static int maxNameLengthFound;
    private static int maxCountLength;

//    private final BlockRenderManager blockModelShapes;
    private final GuiSchematicVerifier guiSchematicVerifier;
    private final WidgetListSchematicVerificationResults listWidget;
    private final SchematicVerifier verifier;
    private final BlockMismatchEntry mismatchEntry;
    @Nullable private final String header1;
    @Nullable private final String header2;
    @Nullable private final String header3;
    @Nullable private final BlockMismatchInfo mismatchInfo;
    private final int count;
    private final boolean isOdd;
    @Nullable private final ButtonGeneric buttonIgnore;

    public WidgetSchematicVerificationResult(int x, int y, int width, int height, boolean isOdd,
            WidgetListSchematicVerificationResults listWidget, GuiSchematicVerifier guiSchematicVerifier,
            BlockMismatchEntry entry, int listIndex)
    {
        super(x, y, width, height, entry, listIndex);

        this.columnCount = 3;
//        this.blockModelShapes = this.mc.getBlockRenderManager();
        this.mismatchEntry = entry;
        this.guiSchematicVerifier = guiSchematicVerifier;
        this.listWidget = listWidget;
        this.verifier = guiSchematicVerifier.getPlacement().getSchematicVerifier();
        this.isOdd = isOdd;

        // Main header
        if (entry.header1 != null && entry.header2 != null)
        {
            this.header1 = entry.header1;
            this.header2 = entry.header2;
            this.header3 = GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST;
            this.mismatchInfo = null;
            this.count = 0;
            this.buttonIgnore = null;
        }
        // Category title
        else if (entry.header1 != null)
        {
            this.header1 = entry.header1;
            this.header2 = null;
            this.header3 = null;
            this.mismatchInfo = null;
            this.count = 0;
            this.buttonIgnore = null;
        }
        // Mismatch entry
        else
        {
            this.header1 = null;
            this.header2 = null;
            this.header3 = null;
            this.mismatchInfo = new BlockMismatchInfo(entry.blockMismatch.stateExpected, entry.blockMismatch.stateFound);
            this.count = entry.blockMismatch.count;

            if (entry.mismatchType != MismatchType.CORRECT_STATE)
            {
                this.buttonIgnore = this.createButton(this.x + this.width, y + 1, ButtonListener.ButtonType.IGNORE_MISMATCH);
            }
            else
            {
                this.buttonIgnore = null;
            }
        }
    }

    public static void setMaxNameLengths(List<BlockMismatch> mismatches)
    {
        maxNameLengthExpected = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_EXPECTED) + GuiBase.TXT_RST);
        maxNameLengthFound    = StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_FOUND) + GuiBase.TXT_RST);
        maxCountLength = 7 * StringUtils.getStringWidth("8");

        for (BlockMismatch entry : mismatches)
        {
            ItemStack stack = ItemUtils.getItemForState(entry.stateExpected);
            String name = BlockMismatchInfo.getDisplayName(entry.stateExpected, stack);
            maxNameLengthExpected = Math.max(maxNameLengthExpected, StringUtils.getStringWidth(name));

            stack = ItemUtils.getItemForState(entry.stateFound);
            name = BlockMismatchInfo.getDisplayName(entry.stateFound, stack);
            maxNameLengthFound = Math.max(maxNameLengthFound, StringUtils.getStringWidth(name));
        }

        maxCountLength = Math.max(maxCountLength, StringUtils.getStringWidth(GuiBase.TXT_BOLD + StringUtils.translate(HEADER_COUNT) + GuiBase.TXT_RST));
    }

    private ButtonGeneric createButton(int x, int y, ButtonListener.ButtonType type)
    {
        ButtonGeneric button = new ButtonGeneric(x, y, -1, true, type.getDisplayName());
        return this.addButton(button, new ButtonListener(type, this.mismatchEntry, this.guiSchematicVerifier));
    }

    @Override
    protected int getCurrentSortColumn()
    {
        return this.verifier.getSortCriteria().ordinal();
    }

    @Override
    protected boolean getSortInReverse()
    {
        return this.verifier.getSortInReverse();
    }

    @Override
    protected int getColumnPosX(int column)
    {
        int x1 = this.x + 4;
        int x2 = x1 + maxNameLengthExpected + 40; // including item icon
        int x3 = x2 + maxNameLengthFound + 40;

        switch (column)
        {
            case 0: return x1;
            case 1: return x2;
            case 2: return x3;
            case 3: return x3 + maxCountLength + 20;
            default: return x1;
        }
    }

    @Override
    protected boolean onMouseClickedImpl(int mouseX, int mouseY, int mouseButton)
    {
        if (super.onMouseClickedImpl(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        if (this.mismatchEntry.type != BlockMismatchEntry.Type.HEADER)
        {
            return false;
        }

        int column = this.getMouseOverColumn(mouseX, mouseY);

        switch (column)
        {
            case 0:
                this.verifier.setSortCriteria(SortCriteria.NAME_EXPECTED);
                break;
            case 1:
                this.verifier.setSortCriteria(SortCriteria.NAME_FOUND);
                break;
            case 2:
                this.verifier.setSortCriteria(SortCriteria.COUNT);
                break;
            default:
                return false;
        }

        // Re-create the widgets
        this.listWidget.refreshEntries();

        return true;
    }

    @Override
    public boolean canSelectAt(int mouseX, int mouseY, int mouseButton)
    {
        return (this.buttonIgnore == null || mouseX < this.buttonIgnore.getX()) && super.canSelectAt(mouseX, mouseY, mouseButton);
    }

    protected boolean shouldRenderAsSelected()
    {
        if (this.mismatchEntry.type == BlockMismatchEntry.Type.CATEGORY_TITLE)
        {
            return this.verifier.isMismatchCategorySelected(this.mismatchEntry.mismatchType);
        }
        else if (this.mismatchEntry.type == BlockMismatchEntry.Type.DATA)
        {
            return this.verifier.isMismatchEntrySelected(this.mismatchEntry.blockMismatch);
        }

        return false;
    }

    @Override
    public void render(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        selected = this.shouldRenderAsSelected();

        // Default color for even entries
        int color = 0xA0303030;

        // Draw a lighter background for the hovered and the selected entry
        if (selected)
        {
            color = 0xA0707070;
        }
        else if (this.isMouseOver(mouseX, mouseY))
        {
            color = 0xA0505050;
        }
        // Draw a slightly darker background for odd entries
        else if (this.isOdd)
        {
            color = 0xA0101010;
        }

        RenderUtils.drawRect(drawContext, this.x, this.y, this.width, this.height, color);

        if (selected)
        {
            RenderUtils.drawOutline(drawContext, this.x, this.y, this.width, this.height, 0xFFE0E0E0);
        }

        int x1 = this.getColumnPosX(0);
        int x2 = this.getColumnPosX(1);
        int x3 = this.getColumnPosX(2);
        int y = this.y + 7;
        color = 0xFFFFFFFF;

        if (this.header1 != null && this.header2 != null)
        {
            this.drawString(drawContext, x1, y, color, this.header1);
            this.drawString(drawContext, x2, y, color, this.header2);
            this.drawString(drawContext, x3, y, color, this.header3);

            this.renderColumnHeader(drawContext, mouseX, mouseY, Icons.ARROW_DOWN, Icons.ARROW_UP);
        }
        else if (this.header1 != null)
        {
            this.drawString(drawContext, this.x + 4, this.y + 7, color, this.header1);
        }
        else if (this.mismatchInfo != null &&
                (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE ||
                 this.mismatchEntry.blockMismatch.stateExpected.isAir() == false)) 
        {
            this.drawString(drawContext, x1 + 20, y, color, this.mismatchInfo.nameExpected);

            if (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE)
            {
                this.drawString(drawContext, x2 + 20, y, color, this.mismatchInfo.nameFound);
            }

            this.drawString(drawContext, x3, y, color, String.valueOf(this.count));

            y = this.y + 3;
            RenderUtils.drawRect(drawContext, x1, y, 16, 16, 0x20FFFFFF); // light background for the item

//            boolean useBlockModelConfig = Configs.Visuals.SCHEMATIC_VERIFIER_BLOCK_MODELS.getBooleanValue();
            boolean useBlockModelConfig = false;
            boolean hasModelExpected = this.mismatchInfo.stateExpected.getRenderType() == BlockRenderType.MODEL;
            boolean hasModelFound    = this.mismatchInfo.stateFound.getRenderType() == BlockRenderType.MODEL;
            boolean isAirItemExpected = this.mismatchInfo.stackExpected.isEmpty();
            boolean isAirItemFound    = this.mismatchInfo.stackFound.isEmpty();
            boolean useBlockModelExpected = hasModelExpected && (isAirItemExpected || useBlockModelConfig || this.mismatchInfo.stateExpected.getBlock() == Blocks.FLOWER_POT);
            boolean useBlockModelFound    = hasModelFound    && (isAirItemFound    || useBlockModelConfig || this.mismatchInfo.stateFound.getBlock() == Blocks.FLOWER_POT);

            //RenderUtils.enableDiffuseLightingGui3D();

//            BlockStateModel model;

            if (useBlockModelExpected && RenderUtils.stateModelHasQuads(this.mismatchInfo.stateExpected))
            {
//                model = this.blockModelShapes.getModel(this.mismatchInfo.stateExpected);
                renderModelInGui(drawContext, x1, y, 1, this.mismatchInfo.stateExpected);
            }
            else
            {
                drawContext.drawItem(this.mismatchInfo.stackExpected, x1, y);
                drawContext.drawStackOverlay(this.textRenderer, this.mismatchInfo.stackExpected, x1, y);
            }

            if (this.mismatchEntry.mismatchType != MismatchType.CORRECT_STATE)
            {
                RenderUtils.drawRect(drawContext, x2, y, 16, 16, 0x20FFFFFF); // light background for the item

                if (useBlockModelFound && RenderUtils.stateModelHasQuads(this.mismatchInfo.stateFound))
                {
//                    model = this.blockModelShapes.getModel(this.mismatchInfo.stateFound);
                    renderModelInGui(drawContext, x2, y, 1, this.mismatchInfo.stateFound);
                }
                else
                {
                    drawContext.drawItem(this.mismatchInfo.stackFound, x2, y);
                    drawContext.drawStackOverlay(this.textRenderer, this.mismatchInfo.stackFound, x2, y);
                }
            }
        }

        super.render(drawContext, mouseX, mouseY, selected);
    }

    @Override
    public void postRenderHovered(DrawContext drawContext, int mouseX, int mouseY, boolean selected)
    {
        if (this.mismatchInfo != null && this.buttonIgnore != null && mouseX < this.buttonIgnore.getX())
        {
            Matrix3x2fStack matrixStack = drawContext.getMatrices();
            matrixStack.pushMatrix();
            matrixStack.translate(0, 0);    // 200

            int x = mouseX + 10;
            int y = mouseY;
            int width = this.mismatchInfo.getTotalWidth();
            int height = this.mismatchInfo.getTotalHeight();

            if (x + width > GuiUtils.getCurrentScreenWidth())
            {
                x = mouseX - width - 10;
            }

            if (y + height > GuiUtils.getCurrentScreenHeight())
            {
                y = mouseY - height - 2;
            }

            this.mismatchInfo.toggleUseBackgroundMask(true);
            this.mismatchInfo.render(drawContext, x, y, this.mc);

            matrixStack.popMatrix();
        }
    }

    public static class BlockMismatchInfo
    {
        private final BlockState stateExpected;
        private final BlockState stateFound;
        private final ItemStack stackExpected;
        private final ItemStack stackFound;
        private final String blockRegistrynameExpected;
        private final String blockRegistrynameFound;
        private final String nameExpected;
        private final String nameFound;
        private final int totalWidth;
        private final int totalHeight;
        private final int columnWidthExpected;
        private boolean useBackgroundMask = false;

        public BlockMismatchInfo(BlockState stateExpected, BlockState stateFound)
        {
            this.stateExpected = stateExpected;
            this.stateFound = stateFound;

            this.stackExpected = ItemUtils.getItemForState(this.stateExpected);
            this.stackFound = ItemUtils.getItemForState(this.stateFound);

            Block blockExpected = this.stateExpected.getBlock();
            Block blockFound = this.stateFound.getBlock();
            Identifier rl1 = Registries.BLOCK.getId(blockExpected);
            Identifier rl2 = Registries.BLOCK.getId(blockFound);

            this.blockRegistrynameExpected = rl1 != null ? rl1.toString() : "<null>";
            this.blockRegistrynameFound = rl2 != null ? rl2.toString() : "<null>";

            this.nameExpected = getDisplayName(stateExpected, this.stackExpected);
            this.nameFound =    getDisplayName(stateFound,    this.stackFound);

            List<String> propsExpected = BlockUtils.getFormattedBlockStateProperties(this.stateExpected, " = ");
            List<String> propsFound = BlockUtils.getFormattedBlockStateProperties(this.stateFound, " = ");

            int w1 = Math.max(StringUtils.getStringWidth(this.nameExpected) + 20, StringUtils.getStringWidth(this.blockRegistrynameExpected));
            int w2 = Math.max(StringUtils.getStringWidth(this.nameFound) + 20, StringUtils.getStringWidth(this.blockRegistrynameFound));
            w1 = Math.max(w1, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(propsExpected));
            w2 = Math.max(w2, fi.dy.masa.litematica.render.RenderUtils.getMaxStringRenderLength(propsFound));

            this.columnWidthExpected = w1;
            this.totalWidth = this.columnWidthExpected + w2 + 40;
            this.totalHeight = Math.max(propsExpected.size(), propsFound.size()) * (StringUtils.getFontHeight() + 2) + 60;
        }

        public static String getDisplayName(BlockState state, ItemStack stack)
        {
            Block block = state.getBlock();
            String key = block.getTranslationKey() + ".name";
            String name = StringUtils.translate(key);
            name = key.equals(name) == false ? name : stack.getName().getString();

            return name;
        }

        public int getTotalWidth()
        {
            return this.totalWidth;
        }

        public int getTotalHeight()
        {
            return this.totalHeight;
        }

        public void toggleUseBackgroundMask(boolean toggle)
        {
            this.useBackgroundMask = toggle;
        }

        public void render(DrawContext drawContext, int x, int y, MinecraftClient mc)
        {
            if (this.stateExpected != null && this.stateFound != null)
            {
                if (this.useBackgroundMask)
                {
                    fi.dy.masa.litematica.render.RenderUtils.renderBackgroundMask(drawContext, x + 1, y + 1, this.totalWidth - 1, this.totalHeight - 1);
                }

                Matrix3x2fStack matrixStack = drawContext.getMatrices();
//                matrixStack.push();

                RenderUtils.drawOutlinedBox(drawContext, x, y, this.totalWidth, this.totalHeight, 0xFF000000, GuiBase.COLOR_HORIZONTAL_BAR);

                int x1 = x + 10;
                int x2 = x + this.columnWidthExpected + 30;
                y += 4;

                TextRenderer textRenderer = mc.textRenderer;
                String pre = GuiBase.TXT_WHITE + GuiBase.TXT_BOLD;
                String strExpected = pre + StringUtils.translate("litematica.gui.label.schematic_verifier.expected") + GuiBase.TXT_RST;
                String strFound =    pre + StringUtils.translate("litematica.gui.label.schematic_verifier.found") + GuiBase.TXT_RST;
                drawContext.drawText(textRenderer, strExpected, x1, y, 0xFFFFFFFF,false);
                drawContext.drawText(textRenderer, strFound,    x2, y, 0xFFFFFFFF,false);

                y += 12;

//                boolean useBlockModelConfig = Configs.Visuals.SCHEMATIC_VERIFIER_BLOCK_MODELS.getBooleanValue();
                boolean useBlockModelConfig = false;
                boolean hasModelExpected = this.stateExpected.getRenderType() == BlockRenderType.MODEL;
                boolean hasModelFound    = this.stateFound.getRenderType() == BlockRenderType.MODEL;
                boolean isAirItemExpected = this.stackExpected.isEmpty();
                boolean isAirItemFound    = this.stackFound.isEmpty();
                boolean useBlockModelExpected = hasModelExpected && (isAirItemExpected || useBlockModelConfig || this.stateExpected.getBlock() == Blocks.FLOWER_POT);
                boolean useBlockModelFound    = hasModelFound    && (isAirItemFound    || useBlockModelConfig || this.stateFound.getBlock() == Blocks.FLOWER_POT);
//                BlockRenderManager blockModelShapes = mc.getBlockRenderManager();

                //mc.getRenderItem().zLevel += 100;
                RenderUtils.drawRect(drawContext, x1, y, 16, 16, 0x50C0C0C0); // light background for the item
                RenderUtils.drawRect(drawContext, x2, y, 16, 16, 0x50C0C0C0); // light background for the item

                int iconY = y;

                //RenderSystem.disableBlend();
                //RenderUtils.disableDiffuseLighting();

                drawContext.drawText(textRenderer, this.nameExpected, x1 + 20, y + 4, 0xFFFFFFFF,false);
                drawContext.drawText(textRenderer, this.nameFound,    x2 + 20, y + 4, 0xFFFFFFFF,false);

                y += 20;
                drawContext.drawText(textRenderer, this.blockRegistrynameExpected, x1, y, 0xFF4060FF,false);
                drawContext.drawText(textRenderer, this.blockRegistrynameFound,    x2, y, 0xFF4060FF,false);
                y += StringUtils.getFontHeight() + 4;

                List<String> propsExpected = BlockUtils.getFormattedBlockStateProperties(this.stateExpected, " = ");
                List<String> propsFound = BlockUtils.getFormattedBlockStateProperties(this.stateFound, " = ");
                RenderUtils.renderText(drawContext, x1, y, 0xFFB0B0B0, propsExpected);
                RenderUtils.renderText(drawContext, x2, y, 0xFFB0B0B0, propsFound);

//                BlockStateModel model;

                //TODO: RenderSystem.disableLighting();
                //RenderUtils.enableDiffuseLightingGui3D();

                if (useBlockModelExpected &&
                    RenderUtils.stateModelHasQuads(this.stateExpected))
                {
//                    model = blockModelShapes.getModel(this.stateExpected);
                    renderModelInGui(drawContext, x1, iconY, 1, this.stateExpected);
                }
                else
                {
                    drawContext.drawItem(this.stackExpected, x1, iconY);
                    drawContext.drawStackOverlay(textRenderer, this.stackExpected, x1, iconY);
                }

                if (useBlockModelFound)
                {
//                    model = blockModelShapes.getModel(this.stateFound);
                    renderModelInGui(drawContext, x2, iconY, 1, this.stateFound);
                }
                else
                {
                    drawContext.drawItem(this.stackFound, x2, iconY);
                    drawContext.drawStackOverlay(textRenderer, this.stackFound, x2, iconY);
                }

//                mc.getRenderItem().zLevel -= 100;
//                matrixStack.pop();
            }
        }
    }

    public static void renderModelInGui(DrawContext drawContext, int x, int y, float z, BlockState state)
    {
//        Matrix3x2fStack matrixStack = drawContext.getMatrices();

        if (state.getBlock() == Blocks.AIR)
        {
            return;
        }

        int size = 16;
        float scale = 0.625f;

        // FIXME
//        RenderUtils.addSpecialElement(drawContext,
//                                      new MaLiLibBlockStateModelGuiElement(
//                                              state,
//                                              x, y,
//                                              size,
//                                              z, scale,
//                                              RenderUtils.peekLastScissor(drawContext))
//        );

//        RenderUtils.bindGuiOverlayTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE, drawContext);
//        mc.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE).setFilter(false, false);

//        RenderUtils.color(1f, 1f, 1f, 1f);

//        MatrixStack matrices = new MatrixStack();
//
////        matrixStack.push();
//        matrixStack.translate((float) (x + 8.0), (float) (y + 8.0)); // z + 100.0
//        matrixStack.scale(16, -16); // 16
//
//        Quaternionf rot = new Quaternionf().rotationXYZ(30 * (float) (Math.PI / 180.0), 225 * (float) (Math.PI / 180.0), 0.0F);
//        matrixStack.mul(rot);
//        matrixStack.scale(0.625f, 0.625f);  // 0.625f
//        matrixStack.translate((float) -0.5, (float) -0.5);  // -0.5
//
//        renderModel(model, state, matrixStack);

//        matrixStack.pop();
    }

//    private static void renderModel(BlockStateModel model, BlockState state, Matrix3x2fStack matrixStack)
//    {
////        BufferAllocator allocator = new BufferAllocator(RenderLayer.DEFAULT_BUFFER_SIZE);
////        VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
////        VertexConsumer vertexConsumer = immediate.getBuffer(RenderLayer.getTranslucent());
//
//        RenderContext ctx = new RenderContext(() -> "litematica:verification_result/quad", RenderPipelines.TRANSLUCENT);
//        BufferBuilder builder = ctx.getBuilder();
//
//        MatrixStack.Entry matrixEntry = matrixStack.peek();
//
//        int l = LightmapTextureManager.pack(15, 15);
//        int[] light = new int[] { l, l, l, l };
//        float[] brightness = new float[] { 0.75f, 0.75f, 0.75f, 1.0f };
//
////            DiffuseLighting.enableGuiDepthLighting();
//
//        List<BlockModelPart> parts = model.getParts(RAND);
//
//        for (BlockModelPart part : parts)
//        {
//            for (Direction face : PositionUtils.ALL_DIRECTIONS)
//            {
//                RAND.setSeed(0);
//                renderQuads(part.getQuads(face), brightness, light, matrixEntry, builder);
//            }
//
//            RAND.setSeed(0);
//            renderQuads(part.getQuads(null), brightness, light, matrixEntry, builder);
//        }
//
////        immediate.draw();
////        allocator.close();
//        try
//        {
//            BuiltBuffer meshData = builder.endNullable();
//
//            if (meshData != null)
//            {
//                ctx.draw(meshData, false);
//                meshData.close();
//            }
//
//            ctx.close();
//        }
//        catch (Exception err)
//        {
//        }
//    }
//
//    private static void renderQuads(List<BakedQuad> quads, float[] brightness, int[] light,
//                                    MatrixStack.Entry matrixEntry, BufferBuilder builder)
//    {
//        for (BakedQuad quad : quads)
//        {
//            renderQuad(quad, brightness, light, matrixEntry, builder);
//        }
//    }
//
//    private static void renderQuad(BakedQuad quad, float[] brightness, int[] light,
//                                   MatrixStack.Entry matrixEntry, BufferBuilder builder)
//    {
//        builder.quad(matrixEntry, quad, brightness, 1.0f, 1.0f, 1.0f, 1.0f, light, OverlayTexture.DEFAULT_UV, true);
//    }

    private static class ButtonListener implements IButtonActionListener
    {
        private final ButtonType type;
        private final GuiSchematicVerifier guiSchematicVerifier;
        private final BlockMismatchEntry mismatchEntry;

        public ButtonListener(ButtonType type, BlockMismatchEntry mismatchEntry, GuiSchematicVerifier guiSchematicVerifier)
        {
            this.type = type;
            this.mismatchEntry = mismatchEntry;
            this.guiSchematicVerifier = guiSchematicVerifier;
        }

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton)
        {
            if (this.type == ButtonType.IGNORE_MISMATCH)
            {
                this.guiSchematicVerifier.getPlacement().getSchematicVerifier().ignoreStateMismatch(this.mismatchEntry.blockMismatch);
                this.guiSchematicVerifier.initGui();
            }
        }

        public enum ButtonType
        {
            IGNORE_MISMATCH ("litematica.gui.button.schematic_verifier.ignore");

            private final String translationKey;

            ButtonType(String translationKey)
            {
                this.translationKey = translationKey;
            }

            public String getDisplayName()
            {
                return StringUtils.translate(this.translationKey);
            }
        }
    }
}
