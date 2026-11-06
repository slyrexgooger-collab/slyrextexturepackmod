package de.texturepackbuilder.gui;

import de.texturepackbuilder.TexturePackBuilder;
import de.texturepackbuilder.core.Dirs;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.PackIO;
import de.texturepackbuilder.core.Selection;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab "Packs": alle Quell-Packs mit Vorschaubild. Eigene Packs hochladen (Dateiauswahl
 * oder per Drag & Drop), ganze Packs übernehmen/abwählen, importierte Packs löschen.
 */
public class PacksScreen extends BaseScreen {
    private static final int ROW = 40;
    private static int page = 0;

    private int listTop, listBottom;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<SourcePack> rowPacks = new ArrayList<>();
    private final Map<SourcePack, Integer> counts = new HashMap<>();
    private SourcePack pendingDelete = null;
    private Button prevBtn, nextBtn;
    private int toolbarEnd;

    public PacksScreen(Screen root) {
        super(root, Tab.PACKS);
        Selection.load();
    }

    @Override
    protected void init() {
        super.init();
        int top = HEADER + 6;
        listTop = top + 26;
        listBottom = height - FOOTER - 28;

        int x = 8;
        Button imp = addRenderableWidget(Button.builder(Component.translatable("tpb.packs.import")
                .withStyle(ChatFormatting.GREEN), b -> openFileDialog()).bounds(x, top, 130, 20).build());
        x += imp.getWidth() + 4;
        Button folder = addRenderableWidget(Button.builder(Component.translatable("tpb.packs.open_folder"),
                b -> openFolder()).bounds(x, top, 110, 20).build());
        x += folder.getWidth() + 4;
        Button rescan = addRenderableWidget(Button.builder(Component.translatable("tpb.packs.rescan"),
                b -> Session.scan()).bounds(x, top, 90, 20).build());
        toolbarEnd = x + rescan.getWidth() + 8;

        prevBtn = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            page--;
            layoutRows();
        }).bounds(8, listBottom + 4, 20, 18).build());
        nextBtn = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            page++;
            layoutRows();
        }).bounds(width - 28, listBottom + 4, 20, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.done"), b -> onClose())
                .bounds(width - 68, height - FOOTER + 4, 60, 20).build());

        counts.clear();
        TextureIndex idx = Session.index();
        if (idx != null) for (SourcePack p : idx.packs) counts.put(p, idx.countFor(p));

        Session.scanIfNeeded();
        layoutRows();
    }

    @Override
    protected int statusWidth() {
        return width - 84;
    }

    private List<SourcePack> packs() {
        TextureIndex idx = Session.index();
        return idx == null ? List.of() : idx.packs;
    }

    private int perPage() {
        return Math.max(1, (listBottom - listTop - 4) / ROW);
    }

    private int pages() {
        return Math.max(1, (packs().size() + perPage() - 1) / perPage());
    }

    private void layoutRows() {
        rowButtons.forEach(this::removeWidget);
        rowButtons.clear();
        rowPacks.clear();
        page = Math.max(0, Math.min(page, pages() - 1));
        int per = perPage();
        List<SourcePack> all = packs();
        for (int i = 0; i < per; i++) {
            int n = page * per + i;
            if (n >= all.size()) break;
            SourcePack p = all.get(n);
            int y = listTop + 4 + i * ROW + (ROW - 20) / 2 - 2;
            int bx = width - 14;
            if (p.deletable()) {
                bx -= 64;
                Component label = p.equals(pendingDelete)
                        ? Component.translatable("tpb.packs.confirm").withStyle(ChatFormatting.GOLD)
                        : Component.translatable("tpb.packs.delete").withStyle(ChatFormatting.RED);
                rowButtons.add(addRenderableWidget(Button.builder(label, b -> {
                    if (p.equals(pendingDelete)) {
                        pendingDelete = null;
                        Session.delete(p);
                    } else {
                        pendingDelete = p;
                        layoutRows();
                    }
                }).bounds(bx, y, 60, 20).build()));
            }
            bx -= 84;
            rowButtons.add(addRenderableWidget(Button.builder(Component.translatable("tpb.packs.drop"),
                    b -> dropAll(p)).bounds(bx, y, 80, 20).build()));
            bx -= 114;
            rowButtons.add(addRenderableWidget(Button.builder(Component.translatable("tpb.packs.take_all"),
                    b -> takeAll(p)).bounds(bx, y, 110, 20).build()));
            rowPacks.add(p);
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages() - 1;
    }

    private void takeAll(SourcePack p) {
        TextureIndex idx = Session.index();
        if (idx == null) return;
        int n = 0;
        for (TextureEntry e : idx.entries.values()) {
            for (Variant v : e.variants) {
                if (v.hasPack(p)) {
                    Selection.chosen.put(e.path, v.hash);
                    n++;
                    break;
                }
            }
        }
        Selection.save();
        Session.status = Component.translatable("tpb.status.took_all", n, p.name());
    }

    private void dropAll(SourcePack p) {
        TextureIndex idx = Session.index();
        if (idx == null) return;
        int n = 0;
        for (TextureEntry e : idx.entries.values()) {
            Variant v = e.find(Selection.chosen.get(e.path));
            if (v != null && v.hasPack(p)) {
                Selection.chosen.remove(e.path);
                n++;
            }
        }
        Selection.save();
        Session.status = Component.translatable("tpb.status.dropped", n, p.name());
    }

    /** Öffnet den System-Dialog zur Dateiauswahl (mehrere ZIPs möglich). */
    private void openFileDialog() {
        String result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.zip"));
            filters.flip();
            result = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("tpb.packs.dialog_title").getString(),
                    System.getProperty("user.home") + File.separator,
                    filters,
                    Component.translatable("tpb.packs.dialog_filter").getString(),
                    true);
        } catch (Throwable t) {
            TexturePackBuilder.LOG.warn("Dateiauswahl nicht verfügbar", t);
            Session.status = Component.translatable("tpb.status.dialog_error");
            return;
        }
        if (result == null || result.isBlank()) return;
        List<Path> paths = new ArrayList<>();
        for (String s : result.split("\\|")) if (!s.isBlank()) paths.add(Path.of(s));
        Session.importFiles(paths);
    }

    private void openFolder() {
        try {
            Files.createDirectories(Dirs.imported());
            Util.getPlatform().openPath(Dirs.imported());
        } catch (Exception e) {
            Session.status = Component.translatable("tpb.status.delete_error", e.getMessage());
        }
    }

    @Override
    protected void drawPanels(GuiGraphics g) {
        Theme.panel(g, 8, listTop, width - 16, listBottom - listTop);
        for (int i = 0; i < rowPacks.size(); i++) {
            int y = listTop + 4 + i * ROW;
            if (i % 2 == 1) g.fill(9, y - 2, width - 9, y + ROW - 2, 0x20FFFFFF);
        }
    }

    @Override
    protected void drawContent(GuiGraphics g, int mouseX, int mouseY) {
        int top = HEADER + 6;
        Theme.text(g, font, Component.translatable("tpb.packs.drop_hint"), toolbarEnd, top + 6,
                width - toolbarEnd - 8, Theme.DIM);

        if (Session.index() == null) {
            Theme.centered(g, font, Component.translatable("tpb.grid.noscan"), width / 2, listTop + 20, width - 40, Theme.DIM);
            return;
        }
        if (rowPacks.isEmpty()) {
            Theme.wrapped(g, font, Component.translatable("tpb.packs.empty"), 20, listTop + 14, width - 40, 4, Theme.DIM);
        }
        int buttonsLeft = rowButtons.isEmpty() ? width - 20 : rowButtons.get(rowButtons.size() - 1).getX();
        for (int i = 0; i < rowPacks.size(); i++) {
            SourcePack p = rowPacks.get(i);
            int y = listTop + 4 + i * ROW;
            int ix = 14, iy = y + 2;
            g.fill(ix, iy, ix + 32, iy + 32, Theme.PANEL_DARK);
            PreviewCache.Tex icon = previews.get("pack|" + p.location(), () -> PackIO.readOnce(p, "pack.png"));
            if (icon != null) PreviewCache.draw(g, icon, ix, iy, 32);
            Theme.outline(g, ix - 1, iy - 1, 34, 34, Theme.BORDER);

            int tx = ix + 40, tw = buttonsLeft - tx - 8;
            Theme.text(g, font, Component.literal(p.name()), tx, y + 5, tw, Theme.TEXT);
            Component meta = Component.translatable(p.origin().key).withColor(p.origin().color)
                    .append(Component.literal("  ·  ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("tpb.packs.textures", counts.getOrDefault(p, 0)).withStyle(ChatFormatting.GRAY));
            Theme.text(g, font, meta, tx, y + 19, tw, Theme.MUTED);
        }
        Theme.centered(g, font, Component.translatable("tpb.grid.page", page + 1, pages()),
                width / 2, listBottom + 9, width - 80, Theme.MUTED);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0 && Theme.inside(mx, my, 8, listTop, width - 16, listBottom - listTop)) {
            page += scrollY > 0 ? -1 : 1;
            layoutRows();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
