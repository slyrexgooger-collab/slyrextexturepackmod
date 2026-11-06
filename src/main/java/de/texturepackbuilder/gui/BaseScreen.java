package de.texturepackbuilder.gui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;
import java.util.List;

/**
 * Gemeinsamer Rahmen: Kopfleiste mit Titel und Tabs, Fußleiste mit Statuszeile,
 * Drag & Drop von Packs auf jedem Tab.
 */
public abstract class BaseScreen extends Screen {
    public enum Tab {
        PVP("tpb.tab.pvp"), TEXTURES("tpb.tab.textures"), PACKS("tpb.tab.packs"), MODRINTH("tpb.tab.modrinth");

        final String key;

        Tab(String key) {
            this.key = key;
        }
    }

    protected static final int HEADER = 24;
    protected static final int FOOTER = 28;

    protected final Screen root;
    private final Tab tab;
    protected final PreviewCache previews = new PreviewCache();
    private Button activeTab;

    protected BaseScreen(Screen root, Tab tab) {
        super(Component.translatable("tpb.title"));
        this.root = root;
        this.tab = tab;
    }

    /** Beim Spielstart: beim allerersten Mal alle PvP-Packs laden, sonst nur schon mal einlesen. */
    public static void onClientStarted() {
        Session.initialDownloadIfNeeded();
        Session.scanIfNeeded();
    }

    @Override
    protected void init() {
        int w = width < 520 ? 58 : 78;
        int x = width - 8 - Tab.values().length * (w + 2) + 2;
        // Neu laden: Packs neu einlesen, Pack neu bauen, Minecraft-Ressourcen komplett neu laden
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.reload"), b -> {
            previews.clear();
            if (TexturesScreen.packName == null) {
                TexturesScreen.packName = Component.translatable("tpb.packname.default").getString();
            }
            Session.reloadAll(TexturesScreen.packName);
        }).bounds(x - 4 - 76, 2, 76, 20).build());
        for (Tab t : Tab.values()) {
            MutableComponent label = Component.translatable(t.key);
            if (t == tab) label = label.withStyle(ChatFormatting.YELLOW);
            Button b = addRenderableWidget(Button.builder(label, btn -> switchTo(t)).bounds(x, 2, w, 20).build());
            if (t == tab) activeTab = b;
            x += w + 2;
        }
    }

    private void switchTo(Tab t) {
        if (t == tab) return;
        minecraft.setScreen(switch (t) {
            case PVP -> new ItemsScreen(root);
            case TEXTURES -> new TexturesScreen(root);
            case PACKS -> new PacksScreen(root);
            case MODRINTH -> new ModrinthScreen(root);
        });
    }

    /** Wird aufgerufen, wenn sich Daten geändert haben (Scan fertig, Import, …). */
    public void onDataChanged() {
        rebuildWidgets();
    }

    /** Hier zeichnen Unterklassen ihre Panels – hinter den Buttons. */
    protected void drawPanels(GuiGraphics g) {}

    /** Hier zeichnen Unterklassen Inhalte über den Buttons (Bilder, Text). */
    protected void drawContent(GuiGraphics g, int mouseX, int mouseY) {}

    /** Platz links in der Fußleiste für die Statuszeile. */
    protected int statusWidth() {
        return width - 16;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.renderBackground(g, mouseX, mouseY, delta);
        g.fill(0, 0, width, HEADER, Theme.BAR);
        g.fill(0, HEADER - 1, width, HEADER, Theme.BORDER);
        g.fill(0, height - FOOTER, width, height, Theme.BAR);
        g.fill(0, height - FOOTER, width, height - FOOTER + 1, Theme.BORDER);
        drawPanels(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawString(font, title, 8, 8, Theme.TEXT);
        if (activeTab != null) {
            g.fill(activeTab.getX() + 2, HEADER - 3, activeTab.getX() + activeTab.getWidth() - 2, HEADER - 1, Theme.ACCENT);
        }
        // Fortschrittsbalken beim PvP-Download
        if (Session.fetching && Session.progressTotal > 0) {
            int bw = Math.min(160, width / 4), bx = 8 + font.width(title) + 12, by = 9;
            g.fill(bx, by, bx + bw, by + 6, Theme.PANEL_DARK);
            g.fill(bx, by, bx + bw * Session.progressDone / Session.progressTotal, by + 6, Theme.ACCENT);
            Theme.outline(g, bx - 1, by - 1, bw + 2, 8, Theme.BORDER);
        }
        previews.beginFrame();
        drawContent(g, mouseX, mouseY);
        int color = Session.busy ? Theme.BADGE : Theme.MUTED;
        Theme.text(g, font, Session.status, 8, height - 18, statusWidth(), color);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        Session.importFiles(paths);
    }

    @Override
    public void removed() {
        previews.clear();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(root);
    }
}
