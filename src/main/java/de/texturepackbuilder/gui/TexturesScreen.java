package de.texturepackbuilder.gui;

import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.PackIO;
import de.texturepackbuilder.core.Search;
import de.texturepackbuilder.core.Selection;
import de.texturepackbuilder.core.TextureNames;
import de.texturepackbuilder.core.TextureNames.Category;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Haupt-Tab: links Kategorien und Filter, in der Mitte Suche und Textur-Raster,
 * rechts Detail-Panel mit großer Vorschau und allen Varianten zum Anklicken.
 */
public class TexturesScreen extends BaseScreen {
    private static final int TILE = 32, GAP = 4, VTILE = 26, VGAP = 4;

    private static String query = "";
    private static Category category = Category.ALL;
    private static boolean onlyMultiple = false, onlySelected = false;
    private static Search.Sort sort = Search.Sort.RELEVANCE;
    private static int page = 0, variantPage = 0;
    private static String focused = null;
    static String packName = null;

    private int top, bottom, sideX, sideW, centerX, centerW, detailX, detailW, gridTop, gridBottom;
    private int previewSize, variantsTop, filterHeaderY;
    private final List<Button> gridButtons = new ArrayList<>();
    private final List<TextureEntry> gridEntries = new ArrayList<>();
    private final List<Button> variantButtons = new ArrayList<>();
    private final List<Variant> variantOptions = new ArrayList<>(); // null = Vanilla
    private List<TextureEntry> results = List.of();
    private Button prevBtn, nextBtn, vPrev, vNext;
    private EditBox nameBox;

    public TexturesScreen(Screen root) {
        super(root, Tab.TEXTURES);
        Selection.load();
        if (packName == null) packName = Component.translatable("tpb.packname.default").getString();
    }

    // ---------- Aufbau ----------

    private void computeLayout() {
        boolean compact = width < 560;
        sideW = compact ? 88 : 106;
        detailW = compact ? 130 : 162;
        top = HEADER + 6;
        bottom = height - FOOTER - 6;
        sideX = 8;
        centerX = sideX + sideW + 6;
        detailX = width - 8 - detailW;
        centerW = Math.max(60, detailX - 6 - centerX);
        gridTop = top + 34;
        gridBottom = bottom - 24;
    }

    @Override
    protected void init() {
        super.init();
        computeLayout();

        // Seitenleiste: Kategorien
        int y = top + 18;
        for (Category c : Category.values()) {
            MutableComponent label = Component.translatable(c.key);
            if (c == category) label = label.withStyle(ChatFormatting.YELLOW);
            addRenderableWidget(Button.builder(label, b -> {
                category = c;
                page = 0;
                rebuildWidgets();
            }).bounds(sideX + 4, y, sideW - 8, 18).build());
            y += 20;
        }
        // Seitenleiste: Filter
        y += 16;
        filterHeaderY = y - 13;
        addRenderableWidget(Button.builder(toggle("tpb.filter.multiple", onlyMultiple), b -> {
            onlyMultiple = !onlyMultiple;
            page = 0;
            rebuildWidgets();
        }).bounds(sideX + 4, y, sideW - 8, 18).build());
        y += 20;
        addRenderableWidget(Button.builder(toggle("tpb.filter.selected", onlySelected), b -> {
            onlySelected = !onlySelected;
            page = 0;
            rebuildWidgets();
        }).bounds(sideX + 4, y, sideW - 8, 18).build());
        y += 20;
        addRenderableWidget(Button.builder(Component.translatable(sort.key), b -> {
            sort = sort.next();
            page = 0;
            rebuildWidgets();
        }).bounds(sideX + 4, y, sideW - 8, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.reset"), b -> {
            Selection.chosen.clear();
            Selection.models.clear();
            Selection.save();
            Session.status = Component.translatable("tpb.status.reset_done");
            refresh();
            layoutGrid();
            layoutDetail();
        }).bounds(sideX + 4, bottom - 22, sideW - 8, 18).build());

        // Suche
        EditBox search = addRenderableWidget(new EditBox(font, centerX, top, centerW, 18, Component.translatable("tpb.search.hint")));
        search.setMaxLength(128);
        search.setHint(Component.translatable("tpb.search.hint").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            page = 0;
            refresh();
            layoutGrid();
        });
        setInitialFocus(search);

        // Seiten
        prevBtn = addRenderableWidget(Button.builder(Component.literal("◀"), b -> turnPage(-1))
                .bounds(centerX, gridBottom + 4, 20, 18).build());
        nextBtn = addRenderableWidget(Button.builder(Component.literal("▶"), b -> turnPage(1))
                .bounds(centerX + centerW - 20, gridBottom + 4, 20, 18).build());

        // Detail: Varianten-Seiten
        vPrev = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            variantPage--;
            layoutDetail();
        }).bounds(detailX + 4, bottom - 22, 18, 18).build());
        vNext = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            variantPage++;
            layoutDetail();
        }).bounds(detailX + detailW - 22, bottom - 22, 18, 18).build());

        // Fußleiste
        int by = height - FOOTER + 4;
        int doneW = 60, buildW = 84, nameW = width < 560 ? 90 : 130;
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.done"), b -> onClose())
                .bounds(width - 8 - doneW, by, doneW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.apply"), b -> Session.build(packName, true))
                .bounds(width - 8 - doneW - 4 - buildW, by, buildW, 20).build());
        nameBox = addRenderableWidget(new EditBox(font, width - 8 - doneW - 4 - buildW - 4 - nameW, by + 1, nameW, 18,
                Component.translatable("tpb.packname.hint")));
        nameBox.setMaxLength(48);
        nameBox.setHint(Component.translatable("tpb.packname.hint").withStyle(ChatFormatting.DARK_GRAY));
        nameBox.setValue(packName);
        nameBox.setResponder(s -> packName = s.isBlank() ? Component.translatable("tpb.packname.default").getString() : s);

        Session.scanIfNeeded();
        refresh();
        layoutGrid();
        layoutDetail();
    }

    private static Component toggle(String key, boolean on) {
        return on
                ? Component.literal("✔ ").withStyle(ChatFormatting.GREEN).append(Component.translatable(key).withStyle(ChatFormatting.WHITE))
                : Component.literal("✖ ").withStyle(ChatFormatting.DARK_GRAY).append(Component.translatable(key));
    }

    @Override
    protected int statusWidth() {
        return nameBox != null ? nameBox.getX() - 16 : super.statusWidth();
    }

    // ---------- Raster ----------

    private void refresh() {
        results = Search.run(Session.index(), new Search.Options(query, category, onlyMultiple, onlySelected, sort));
    }

    private int cols() {
        return Math.max(1, (centerW + GAP) / (TILE + GAP));
    }

    private int rows() {
        return Math.max(1, (gridBottom - gridTop + GAP) / (TILE + GAP));
    }

    private int pages() {
        int per = cols() * rows();
        return Math.max(1, (results.size() + per - 1) / per);
    }

    private void turnPage(int delta) {
        page += delta;
        layoutGrid();
    }

    private void layoutGrid() {
        gridButtons.forEach(this::removeWidget);
        gridButtons.clear();
        gridEntries.clear();
        int cols = cols(), per = cols * rows();
        page = Math.max(0, Math.min(page, pages() - 1));
        int startX = centerX + (centerW - (cols * (TILE + GAP) - GAP)) / 2;
        for (int i = 0; i < per; i++) {
            int n = page * per + i;
            if (n >= results.size()) break;
            TextureEntry e = results.get(n);
            int x = startX + (i % cols) * (TILE + GAP);
            int y = gridTop + (i / cols) * (TILE + GAP);
            gridButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
                focused = e.path;
                variantPage = 0;
                layoutDetail();
            }).bounds(x, y, TILE, TILE).build()));
            gridEntries.add(e);
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages() - 1;
    }

    // ---------- Detail ----------

    private TextureEntry focusedEntry() {
        TextureIndex idx = Session.index();
        return idx == null || focused == null ? null : idx.entries.get(focused);
    }

    private int vCols() {
        return Math.max(1, (detailW - 12 + VGAP) / (VTILE + VGAP));
    }

    private int vPer() {
        int rows = Math.max(1, (bottom - 50 - variantsTop + VGAP) / (VTILE + VGAP));
        return vCols() * rows;
    }

    private void layoutDetail() {
        variantButtons.forEach(this::removeWidget);
        variantButtons.clear();
        variantOptions.clear();
        previewSize = Math.min(detailW - 24, 80);
        variantsTop = top + 30 + previewSize + 18;

        TextureEntry e = focusedEntry();
        List<Variant> opts = new ArrayList<>();
        if (e != null) {
            opts.add(null);
            opts.addAll(e.variants);
        }
        int per = vPer();
        int pages = Math.max(1, (opts.size() + per - 1) / per);
        variantPage = Math.max(0, Math.min(variantPage, pages - 1));
        int cols = vCols();
        int startX = detailX + (detailW - (cols * (VTILE + VGAP) - VGAP)) / 2;
        for (int i = 0; i < per; i++) {
            int n = variantPage * per + i;
            if (n >= opts.size()) break;
            Variant v = opts.get(n);
            int x = startX + (i % cols) * (VTILE + VGAP);
            int y = variantsTop + (i / cols) * (VTILE + VGAP);
            variantButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> choose(v))
                    .bounds(x, y, VTILE, VTILE).build()));
            variantOptions.add(v);
        }
        vPrev.visible = vNext.visible = pages > 1;
        vPrev.active = variantPage > 0;
        vNext.active = variantPage < pages - 1;
    }

    private void choose(Variant v) {
        if (focused == null) return;
        if (v == null) Selection.chosen.remove(focused);
        else Selection.chosen.put(focused, v.hash);
        Selection.save();
        if (onlySelected) {
            refresh();
            layoutGrid();
        }
    }

    private PreviewCache.Tex preview(String path, Variant v) {
        return v != null
                ? previews.get(path + "|" + v.hash, () -> PackIO.readOnce(v.primary().pack(), v.primary().entry()))
                : previews.get(path + "|vanilla", () -> PackIO.readVanilla(path));
    }

    // ---------- Zeichnen ----------

    @Override
    protected void drawPanels(GuiGraphics g) {
        Theme.panel(g, sideX, top, sideW, bottom - top);
        Theme.panel(g, centerX - 4, gridTop - 4, centerW + 8, gridBottom - gridTop + 4);
        Theme.panel(g, detailX, top, detailW, bottom - top);
    }

    @Override
    protected void drawContent(GuiGraphics g, int mouseX, int mouseY) {
        Theme.heading(g, font, Component.translatable("tpb.side.categories"), sideX + 6, top + 4, sideW - 12);
        Theme.heading(g, font, Component.translatable("tpb.side.filter"), sideX + 6, filterHeaderY, sideW - 12);

        TextureIndex idx = Session.index();
        TextureEntry hovered = null;
        for (int i = 0; i < gridButtons.size(); i++) {
            Button b = gridButtons.get(i);
            TextureEntry e = gridEntries.get(i);
            Variant v = e.find(Selection.chosen.get(e.path));
            PreviewCache.Tex t = preview(e.path, v);
            if (t != null) PreviewCache.draw(g, t, b.getX() + 4, b.getY() + 4, TILE - 8);
            if (v != null) Theme.outline(g, b.getX(), b.getY(), TILE, TILE, Theme.ACCENT);
            if (e.path.equals(focused)) Theme.outline(g, b.getX() - 1, b.getY() - 1, TILE + 2, TILE + 2, Theme.FOCUS);
            if (e.variants.size() > 1) {
                String c = String.valueOf(e.variants.size());
                g.drawString(font, c, b.getX() + TILE - font.width(c) - 2, b.getY() + TILE - 9, Theme.BADGE);
            }
            if (b.isHovered()) hovered = e;
        }

        // Infozeile über dem Raster
        Component info;
        if (hovered != null) {
            info = Component.literal(TextureNames.displayName(hovered.path))
                    .append(Component.literal("  " + TextureNames.shortPath(hovered.path)).withStyle(ChatFormatting.GRAY));
        } else if (idx == null) {
            info = Component.translatable("tpb.grid.noscan");
        } else {
            info = Component.translatable("tpb.grid.info", results.size(), Selection.chosen.size());
        }
        Theme.text(g, font, info, centerX, top + 23, centerW, hovered != null ? Theme.TEXT : Theme.MUTED);

        if (idx != null && results.isEmpty()) {
            Theme.centered(g, font, Component.translatable("tpb.grid.empty"), centerX + centerW / 2,
                    (gridTop + gridBottom) / 2 - 4, centerW - 10, Theme.DIM);
        }
        Theme.centered(g, font, Component.translatable("tpb.grid.page", page + 1, pages()),
                centerX + centerW / 2, gridBottom + 9, centerW - 50, Theme.MUTED);

        drawDetail(g);
    }

    private void drawDetail(GuiGraphics g) {
        int x = detailX + 6, w = detailW - 12;
        TextureEntry e = focusedEntry();
        if (e == null) {
            Theme.heading(g, font, Component.translatable("tpb.detail.title"), x, top + 4, w);
            Theme.wrapped(g, font, Component.translatable("tpb.detail.none"), x, top + 22, w, 8, Theme.DIM);
            return;
        }
        Theme.heading(g, font, Component.literal(TextureNames.displayName(e.path)), x, top + 4, w);
        Theme.text(g, font, Component.literal(TextureNames.shortPath(e.path)), x, top + 16, w, Theme.DIM);

        // Welche Variante zeigen? Die überfahrene, sonst die gewählte.
        int hoverIdx = -1;
        for (int i = 0; i < variantButtons.size(); i++) if (variantButtons.get(i).isHovered()) hoverIdx = i;
        Variant chosen = e.find(Selection.chosen.get(e.path));
        Variant shown = hoverIdx >= 0 ? variantOptions.get(hoverIdx) : chosen;

        int px = detailX + (detailW - previewSize) / 2, py = top + 30;
        PreviewCache.checker(g, px, py, previewSize);
        PreviewCache.Tex big = preview(e.path, shown);
        if (big != null) PreviewCache.draw(g, big, px, py, previewSize);
        Theme.outline(g, px - 1, py - 1, previewSize + 2, previewSize + 2, Theme.BORDER);

        Theme.text(g, font, Component.translatable("tpb.detail.variants", e.variants.size() + 1),
                x, variantsTop - 12, w, Theme.MUTED);

        for (int i = 0; i < variantButtons.size(); i++) {
            Button b = variantButtons.get(i);
            Variant v = variantOptions.get(i);
            PreviewCache.Tex t = preview(e.path, v);
            if (t != null) PreviewCache.draw(g, t, b.getX() + 3, b.getY() + 3, VTILE - 6);
            if (v == null) g.drawString(font, "V", b.getX() + 2, b.getY() + 2, Theme.DIM);
            boolean sel = v == null ? chosen == null : v == chosen;
            if (sel) Theme.outline(g, b.getX() - 1, b.getY() - 1, VTILE + 2, VTILE + 2, Theme.ACCENT);
        }

        // Info zur überfahrenen oder gewählten Variante
        Variant infoV = hoverIdx >= 0 ? variantOptions.get(hoverIdx) : chosen;
        Component info;
        if (infoV == null) {
            info = Component.translatable("tpb.detail.vanilla_desc");
        } else {
            MutableComponent c = Component.literal(infoV.width + "×" + infoV.height);
            if (infoV.animated()) c.append(" · ").append(Component.translatable("tpb.detail.animated"));
            c.append(" · ").append(Component.translatable("tpb.detail.in", infoV.sourceNames()));
            info = c;
        }
        int infoY = bottom - 46;
        if (hoverIdx < 0) {
            Theme.text(g, font, infoV == null ? Component.translatable("tpb.detail.chosen_vanilla")
                    : Component.translatable("tpb.detail.chosen"), x, infoY - 10, w, Theme.ACCENT);
        }
        Theme.wrapped(g, font, info, x, infoY, w, 2, Theme.MUTED);
    }

    // ---------- Eingabe ----------

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0 && Theme.inside(mx, my, centerX - 4, gridTop - 4, centerW + 8, gridBottom - gridTop + 4)) {
            turnPage(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (scrollY != 0 && Theme.inside(mx, my, detailX, variantsTop, detailW, bottom - variantsTop)) {
            variantPage += scrollY > 0 ? -1 : 1;
            layoutDetail();
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
