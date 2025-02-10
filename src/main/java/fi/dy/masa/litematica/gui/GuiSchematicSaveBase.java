package fi.dy.masa.litematica.gui;

import javax.annotation.Nullable;

import net.minecraft.client.gui.DrawContext;

import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.ISelectionListener;
import fi.dy.masa.malilib.gui.widgets.WidgetCheckBox;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntry;
import fi.dy.masa.malilib.gui.widgets.WidgetFileBrowserBase.DirectoryEntryType;
import fi.dy.masa.malilib.util.FileNameUtils;
import fi.dy.masa.malilib.util.KeyCodes;
import fi.dy.masa.malilib.util.StringUtils;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;

public abstract class GuiSchematicSaveBase extends GuiSchematicBrowserBase implements ISelectionListener<DirectoryEntry>
{
    protected GuiTextFieldGeneric textField;
    protected WidgetCheckBox checkboxIgnoreEntities;
    protected WidgetCheckBox checkboxVisibleOnly;
    protected WidgetCheckBox checkboxIncludeSupportBlocks;
    protected final WidgetCheckBox checkboxSaveFromSchematicWorld;
    protected String lastText = "";
    protected String defaultText = "";
    @Nullable protected final LitematicaSchematic schematic;

    public GuiSchematicSaveBase(@Nullable LitematicaSchematic schematic)
    {
        super(10, 80);

        this.schematic = schematic;

        this.textField = new GuiTextFieldGeneric(10, 32, 160, 20, this.textRenderer);
        this.textField.setMaxLengthWrapper(256);
        this.textField.setFocusedWrapper(true);

        this.checkboxSaveFromSchematicWorld = new WidgetCheckBox(0, 0, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.save_from_schematic_world"), StringUtils.translate("litematica.gui.label.schematic_save.hover_info.save_from_schematic_world"));
    }

    @Override
    public int getBrowserHeight()
    {
        return this.getScreenHeight() - 80;
    }

    @Override
    public void initGui()
    {
        super.initGui();

        boolean focused = this.textField.isFocusedWrapper();
        String text = this.textField.getTextWrapper();
        this.textField = new GuiTextFieldGeneric(10, 32, this.getScreenWidth() - 260, 18, this.textRenderer);
        this.textField.setTextWrapper(text);
        this.textField.setFocusedWrapper(focused);

        DirectoryEntry entry = this.getListWidget().getLastSelectedEntry();

        // Only set the text field contents if it hasn't been set already.
        // This prevents overwriting any user input text when switching to a newly created directory.
        if (this.lastText.isEmpty())
        {
            if (entry != null && entry.getType() != DirectoryEntryType.DIRECTORY && entry.getType() != DirectoryEntryType.INVALID)
            {
                this.setTextFieldText(FileNameUtils.getFileNameWithoutExtension(entry.getName()));
            }
            else if (this.schematic != null)
            {
                this.setTextFieldText(this.schematic.getMetadata().getName());
            }
            else
            {
                this.setTextFieldText(this.defaultText);
            }
        }

        int x = this.textField.getXWrapper() + this.textField.getWidthWrapper() + 4;
        int y = 28;

        String str = StringUtils.translate("litematica.gui.label.schematic_save.checkbox.ignore_entities");
        this.checkboxIgnoreEntities = new WidgetCheckBox(x, y, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, str);
        this.addWidget(this.checkboxIgnoreEntities);

        this.checkboxSaveFromSchematicWorld.setPosition(x, y + 12);
        this.addWidget(this.checkboxSaveFromSchematicWorld);

//        this.checkboxVisibleOnly = new WidgetCheckBox(x, y + 24, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, "Visible blocks only [experimental quick hax]");
        this.checkboxVisibleOnly = new WidgetCheckBox(x, y + 24, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.visible_blocks_only"));
        this.addWidget(this.checkboxVisibleOnly);

        this.checkboxIncludeSupportBlocks = new WidgetCheckBox(x, y + 36, Icons.CHECKBOX_UNSELECTED, Icons.CHECKBOX_SELECTED, StringUtils.translate("litematica.gui.label.schematic_save.checkbox.support_blocks"), StringUtils.translate("litematica.gui.label.schematic_save.hover_info.support_blocks"));
        this.addWidget(this.checkboxIncludeSupportBlocks);

        this.createButton(10, 54, ButtonType.SAVE);
    }

    protected void setTextFieldText(String text)
    {
        this.lastText = text;
        this.textField.setTextWrapper(text);
    }

    protected String getTextFieldText()
    {
        return this.textField.getTextWrapper();
    }

    protected abstract IButtonActionListener createButtonListener(ButtonType type);

    private int createButton(int x, int y, ButtonType type)
    {
        String label = StringUtils.translate(type.getLabelKey());
        int width = this.getStringWidth(label) + 10;

        ButtonGeneric button;

        if (type == ButtonType.SAVE)
        {
            button = new ButtonGeneric(x, y, width, 20, label, "litematica.gui.label.schematic_save.hover_info.hold_shift_to_overwrite");
        }
        else
        {
            button = new ButtonGeneric(x, y, width, 20, label);
        }

        this.addButton(button, this.createButtonListener(type));

        return x + width + 4;
    }

    @Override
    public void setString(String string)
    {
        this.setNextMessageType(MessageType.ERROR);
        super.setString(string);
    }

    @Override
    public void drawContents(DrawContext drawContext, int mouseX, int mouseY, float partialTicks)
    {
        super.drawContents(drawContext, mouseX, mouseY, partialTicks);

        this.textField.renderWrapper(drawContext, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onSelectionChange(@Nullable DirectoryEntry entry)
    {
        if (entry != null && entry.getType() != DirectoryEntryType.DIRECTORY && entry.getType() != DirectoryEntryType.INVALID)
        {
            this.setTextFieldText(FileNameUtils.getFileNameWithoutExtension(entry.getName()));
        }
    }

    @Override
    protected ISelectionListener<DirectoryEntry> getSelectionListener()
    {
        return this;
    }

    @Override
    public boolean onMouseClicked(int mouseX, int mouseY, int mouseButton)
    {
        if (this.textField.mouseClickedWrapper(mouseX, mouseY, mouseButton))
        {
            return true;
        }

        return super.onMouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean onKeyTyped(int keyCode, int scanCode, int modifiers)
    {
        if (this.textField.keyPressedWrapper(keyCode, scanCode, modifiers))
        {
            this.getListWidget().clearSelection();
            return true;
        }
        else if (keyCode == KeyCodes.KEY_TAB)
        {
            this.textField.setFocusedWrapper(! this.textField.isFocusedWrapper());
            return true;
        }

        return super.onKeyTyped(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean onCharTyped(char charIn, int modifiers)
    {
        if (this.textField.charTypedWrapper(charIn, modifiers))
        {
            this.getListWidget().clearSelection();
            return true;
        }

        return super.onCharTyped(charIn, modifiers);
    }

    public enum ButtonType
    {
        SAVE ("litematica.gui.button.save_schematic");

        private final String labelKey;

        private ButtonType(String labelKey)
        {
            this.labelKey = labelKey;
        }

        public String getLabelKey()
        {
            return this.labelKey;
        }
    }
}
