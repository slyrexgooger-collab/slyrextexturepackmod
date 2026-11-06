package de.texturepackbuilder.gui;

import de.texturepackbuilder.core.Model.Origin;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.PackIO;
import de.texturepackbuilder.core.PvpCatalog;
import de.texturepackbuilder.core.PvpCatalog.Item;
import de.texturepackbuilder.core.PvpCatalog.Mode;
import de.texturepackbuilder.core.Selection;
import de.texturepackbuilder.core.Styles;
import de.texturepackbuilder.core.Styles.Style;
import de.texturepackbuilder.core.TextureNames;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PvP-Tab im Stil eines Texture-Managers: links die PvP-Items (Sammlung/Extras, gefiltert nach
 * Spielmodus), rechts alle Styles des gewählten Items als Karten aus allen Packs.
 * Klick auf eine Karte wählt den Style, "Anwenden" baut das Pack und aktiviert es sofort im Spiel.
 */
public class ItemsScreen extends BaseScreen {
    private enum Filter {
        ALL("tpb.filter.all"), YOURS("tpb.filter.yours"), MODRINTH("tpb.filter.modrinth"), FAVORITES("tpb.filter.favorites");
        final String key;

        Filter(String key) {
            this.key = key;
        }
    }

    private enum Sort {
        NAME("tpb.sort.az"), NEWEST("tpb.sort.newest"), OLDEST("tpb.sort.oldest");
        final String key;

        Sort(String key) {
            this.key = key;
        }
    }

    private static final int ROW = 26, CARD_W = 92, CARD_H = 80, GAP = 6, ICON = 44;
    private static final long NEW_MS = 7L * 24 * 60 * 60 * 1000;

    private static boolean extras = false;
    private static int modeIdx = 0;
    private static String itemId = null;
    private static Filter filter = Filter.ALL;
    private static Sort sort = Sort.NAME;
    private static String query = "";
    private static int listScroll = 0, page = 0;
    /** Nach jedem Klick automatisch bauen und im Spiel aktivieren. */
    private static boolean autoApply = true;
    /** Zeitpunkt für das automatische Anwenden (kurz nach dem letzten Klick, damit schnelle Klicks gesammelt werden). */
    private static long applyAt = 0;

    private int top, bottom, sideW, mainX, mainW, listTop, listBottom, gridTop, gridBottom;
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Item> rowItems = new ArrayList<>();
    private final List<Button> cardButtons = new ArrayList<>();
    private final List<Button> starButtons = new ArrayList<>();
    private final List<Style> cardStyles = new ArrayList<>(); // null = Vanilla
    private List<Item> visibleItems = List.of();
    private List<Style> results = List.of();
    private Button prevBtn, nextBtn, fetchItemBtn;
    private EditBox nameBox;

    public ItemsScreen(Screen root) {
        super(root, Tab.PVP);
        Selection.load();
        if (TexturesScreen.packName == null) {
            TexturesScreen.packName = Component.translatable("tpb.packname.default").getString();
        }
    }

    private static Mode mode() {
        return PvpCatalog.MODES.get(Math.floorMod(modeIdx, PvpCatalog.MODES.size()));
    }

    private Item item() {
        for (Item i : visibleItems) if (i.id().equals(itemId)) return i;
        return visibleItems.isEmpty() ? null : visibleItems.get(0);
    }

    // ---------- Aufbau ----------

    @Override
    protected void init() {
        super.init();
        top = HEADER + 6;
        bottom = height - FOOTER - 6;
        sideW = width < 560 ? 128 : 150;
        mainX = 8 + sideW + 6;
        mainW = width - 8 - mainX;
        listTop = top + 48;
        listBottom = bottom - 26;
        gridTop = top + 50;
        gridBottom = bottom - 24;

        // Sammlung / Extras
        int half = (sideW - 10) / 2;
        addRenderableWidget(Button.builder(tab("tpb.side.collection", !extras), b -> {
            extras = false;
            listScroll = 0;
            itemId = null;
            rebuildWidgets();
        }).bounds(12, top + 4, half, 18).build());
        addRenderableWidget(Button.builder(tab("tpb.side.extras", extras), b -> {
            extras = true;
            listScroll = 0;
            itemId = null;
            rebuildWidgets();
        }).bounds(14 + half, top + 4, half, 18).build());
        // Spielmodus
        addRenderableWidget(Button.builder(Component.translatable("tpb.mode.label", Component.translatable(mode().key())), b -> {
            modeIdx++;
            listScroll = 0;
            itemId = null;
            rebuildWidgets();
        }).bounds(12, top + 24, sideW - 8, 18).build());
        // Alle PvP-Packs des Modus holen
        addRenderableWidget(Button.builder(Component.translatable("tpb.fetch.mode"), b -> {
            List<Item> items = new ArrayList<>();
            for (Item i : PvpCatalog.ITEMS) if (mode().includes(i)) items.add(i);
            Session.fetchPvpPacks(items, 2);
        }).bounds(12, bottom - 22, sideW - 8, 18).build());

        // Filter-Reiter
        int fx = mainX + 6;
        for (Filter f : Filter.values()) {
            Component label = tab(f.key, f == filter);
            int w = font.width(label) + 12;
            addRenderableWidget(Button.builder(label, b -> {
                filter = f;
                page = 0;
                rebuildWidgets();
            }).bounds(fx, top + 26, w, 16).build());
            fx += w + 2;
        }
        // Sortierung (rechts)
        int sx = width - 14;
        for (int i = Sort.values().length - 1; i >= 0; i--) {
            Sort s = Sort.values()[i];
            Component label = tab(s.key, s == sort);
            int w = font.width(label) + 10;
            sx -= w;
            addRenderableWidget(Button.builder(label, b -> {
                sort = s;
                page = 0;
                rebuildWidgets();
            }).bounds(sx, top + 26, w, 16).build());
            sx -= 2;
        }
        // Suche (rechts oben)
        int searchW = Math.min(150, mainW / 3);
        EditBox search = addRenderableWidget(new EditBox(font, width - 14 - searchW, top + 4, searchW, 16,
                Component.translatable("tpb.search.styles")));
        search.setMaxLength(64);
        search.setHint(Component.translatable("tpb.search.styles").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(s -> {
            query = s;
            page = 0;
            refreshResults();
            layoutCards();
        });

        prevBtn = addRenderableWidget(Button.builder(Component.literal("◀"), b -> turnPage(-1))
                .bounds(mainX + 6, gridBottom + 4, 20, 16).build());
        nextBtn = addRenderableWidget(Button.builder(Component.literal("▶"), b -> turnPage(1))
                .bounds(width - 34, gridBottom + 4, 20, 16).build());
        fetchItemBtn = addRenderableWidget(Button.builder(Component.translatable("tpb.fetch.item"), b -> {
            Item it = item();
            if (it != null) Session.fetchPvpPacks(List.of(it), 4);
        }).bounds(mainX + mainW / 2 - 70, gridBottom - 30, 140, 20).build());

        // Fußleiste
        int by = height - FOOTER + 4;
        int doneW = 60, applyW = 96, nameW = width < 560 ? 90 : 130, autoW = 110;
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.done"), b -> onClose())
                .bounds(width - 8 - doneW, by, doneW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.apply").withStyle(ChatFormatting.GREEN),
                b -> Session.build(TexturesScreen.packName, true)).bounds(width - 12 - doneW - applyW, by, applyW, 20).build());
        addRenderableWidget(Button.builder(autoLabel(), b -> {
            autoApply = !autoApply;
            b.setMessage(autoLabel());
        }).bounds(width - 20 - doneW - applyW - nameW - autoW, by, autoW, 20).build());
        nameBox = addRenderableWidget(new EditBox(font, width - 16 - doneW - applyW - nameW, by + 1, nameW, 18,
                Component.translatable("tpb.packname.hint")));
        nameBox.setMaxLength(48);
        nameBox.setValue(TexturesScreen.packName);
        nameBox.setResponder(s -> TexturesScreen.packName =
                s.isBlank() ? Component.translatable("tpb.packname.default").getString() : s);

        Session.scanIfNeeded();
        refreshItems();
        layoutRows();
        refreshResults();
        layoutCards();
    }

    private static Component autoLabel() {
        return autoApply
                ? Component.literal("✔ ").withStyle(ChatFormatting.GREEN).append(Component.translatable("tpb.auto_apply").withStyle(ChatFormatting.WHITE))
                : Component.literal("✖ ").withStyle(ChatFormatting.DARK_GRAY).append(Component.translatable("tpb.auto_apply"));
    }

    @Override
    public void tick() {
        super.tick();
        if (applyAt > 0 && System.currentTimeMillis() >= applyAt) {
            applyAt = 0;
            Session.build(TexturesScreen.packName, true);
        }
    }

    private static Component tab(String key, boolean active) {
        MutableComponent c = Component.translatable(key);
        return active ? c.withStyle(ChatFormatting.YELLOW) : c.withStyle(ChatFormatting.GRAY);
    }

    @Override
    protected int statusWidth() {
        return nameBox != null ? nameBox.getX() - 130 : super.statusWidth();
    }

    @Override
    public void onDataChanged() {
        previews.clear(); // nach einem Ressourcen-Neuladen frisch laden
        rebuildWidgets();
    }

    // ---------- Item-Liste ----------

    private void refreshItems() {
        List<Item> out = new ArrayList<>();
        for (Item i : PvpCatalog.ITEMS) if (i.extra() == extras && mode().includes(i)) out.add(i);
        visibleItems = out;
        if (item() != null) itemId = item().id();
    }

    private int rowsVisible() {
        return Math.max(1, (listBottom - listTop) / ROW);
    }

    private void layoutRows() {
        rowButtons.forEach(this::removeWidget);
        rowButtons.clear();
        rowItems.clear();
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, visibleItems.size() - rowsVisible())));
        for (int r = 0; r < rowsVisible(); r++) {
            int n = listScroll + r;
            if (n >= visibleItems.size()) break;
            Item it = visibleItems.get(n);
            rowButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
                itemId = it.id();
                page = 0;
                refreshResults();
                layoutCards();
            }).bounds(10, listTop + r * ROW, sideW - 4, ROW - 2).build()));
            rowItems.add(it);
        }
    }

    // ---------- Karten ----------

    private boolean passes(Style s) {
        SourcePack p = s.pack();
        switch (filter) {
            case YOURS -> {
                boolean own = false;
                for (SourcePack q : s.packs()) if (q.origin() != Origin.MODRINTH) own = true;
                if (!own) return false;
            }
            case MODRINTH -> {
                boolean mr = false;
                for (SourcePack q : s.packs()) if (q.origin() == Origin.MODRINTH) mr = true;
                if (!mr) return false;
            }
            case FAVORITES -> {
                if (!s.favorite()) return false;
            }
            default -> {
            }
        }
        String q = TextureNames.normalize(query).trim();
        return q.isEmpty() || TextureNames.normalize(p.name()).contains(q);
    }

    private void refreshResults() {
        Item it = item();
        List<Style> out = new ArrayList<>();
        TextureIndex idx = Session.index();
        if (it != null && idx != null) {
            for (Style s : Styles.styles(idx, it)) if (passes(s)) out.add(s);
            Comparator<Style> byName = Comparator.comparing(s -> s.name().toLowerCase());
            out.sort(switch (sort) {
                case NAME -> byName;
                case NEWEST -> Comparator.comparingLong(Style::newest).reversed().thenComparing(byName);
                case OLDEST -> Comparator.comparingLong(Style::newest).thenComparing(byName);
            });
        }
        // Vanilla-Karte immer zuerst (außer bei Filtern, die nur Packs zeigen)
        boolean showVanilla = filter == Filter.ALL && (query.isBlank()
                || "vanilla".contains(TextureNames.normalize(query).trim()));
        List<Style> withVanilla = new ArrayList<>();
        if (showVanilla && it != null) withVanilla.add(null);
        withVanilla.addAll(out);
        results = withVanilla;
    }

    private int cols() {
        return Math.max(1, (mainW - 12 + GAP) / (CARD_W + GAP));
    }

    private int rows() {
        return Math.max(1, (gridBottom - gridTop + GAP) / (CARD_H + GAP));
    }

    private int pages() {
        int per = cols() * rows();
        return Math.max(1, (results.size() + per - 1) / per);
    }

    private void turnPage(int d) {
        page += d;
        layoutCards();
    }

    private void layoutCards() {
        cardButtons.forEach(this::removeWidget);
        starButtons.forEach(this::removeWidget);
        cardButtons.clear();
        starButtons.clear();
        cardStyles.clear();
        Item it = item();
        page = Math.max(0, Math.min(page, pages() - 1));
        int cols = cols(), per = cols * rows();
        int startX = mainX + (mainW - (cols * (CARD_W + GAP) - GAP)) / 2;
        for (int i = 0; i < per; i++) {
            int n = page * per + i;
            if (n >= results.size() || it == null) break;
            Style s = results.get(n);
            int x = startX + (i % cols) * (CARD_W + GAP);
            int y = gridTop + (i / cols) * (CARD_H + GAP);
            if (s != null) {
                // Stern zuerst hinzufügen, damit er Klicks vor der Karte bekommt
                starButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
                    Selection.setFavorite(s.favoriteKeys(), !s.favorite());
                    if (filter == Filter.FAVORITES) {
                        refreshResults();
                        layoutCards();
                    }
                }).bounds(x + CARD_W - 14, y + 2, 12, 12).build()));
            }
            cardButtons.add(addRenderableWidget(Button.builder(Component.empty(), b -> {
                Styles.apply(it, s);
                Session.status = Component.translatable(autoApply ? "tpb.status.style_chosen_auto" : "tpb.status.style_chosen",
                        s == null ? "Vanilla" : s.name(), Component.translatable(it.nameKey()));
                if (autoApply) applyAt = System.currentTimeMillis() + 900;
            }).bounds(x, y, CARD_W, CARD_H).build()));
            cardStyles.add(s);
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages() - 1;
        int real = it == null ? 0 : Styles.styles(Session.index(), it).size();
        fetchItemBtn.visible = Session.index() != null && it != null && real < 3;
    }

    // ---------- Vorschaubilder ----------

    private PreviewCache.Tex vanillaIcon(Item it) {
        return previews.get("vanilla|" + it.id(), () -> {
            for (String p : it.iconPaths()) {
                byte[] d = PackIO.readVanilla(p);
                if (d != null) return d;
            }
            return null;
        });
    }

    private PreviewCache.Tex styleIcon(Item it, Style s) {
        if (s == null || s.iconPath() == null) return vanillaIcon(it);
        return previews.get("style|" + s.key().hashCode() + "|" + s.pack().location(),
                () -> PackIO.readOnce(s.pack(), s.iconPath()));
    }

    private String currentName(TextureIndex idx, Item it) {
        String cur = Styles.current(idx, it);
        if (cur.equals(Styles.VANILLA)) return "Vanilla";
        if (cur.equals(Styles.MIXED)) return Component.translatable("tpb.style.mixed").getString();
        if (cur.equals(Styles.MISSING)) return Component.translatable("tpb.style.missing").getString();
        Style s = Styles.find(idx, it, cur);
        return s == null ? "?" : s.name();
    }

    // ---------- Zeichnen ----------

    @Override
    protected void drawPanels(GuiGraphics g) {
        Theme.panel(g, 8, top, sideW, bottom - top);
        Theme.panel(g, mainX, top, mainW, bottom - top);
        // aktive Zeile hervorheben
        for (int r = 0; r < rowItems.size(); r++) {
            if (rowItems.get(r).id().equals(itemId)) {
                int y = listTop + r * ROW;
                g.fill(10, y, 10 + sideW - 4, y + ROW - 2, 0x30FFFFFF);
                g.fill(10, y, 12, y + ROW - 2, Theme.ACCENT);
            }
        }
    }

    @Override
    protected void drawContent(GuiGraphics g, int mouseX, int mouseY) {
        TextureIndex idx = Session.index();

        // Item-Liste
        for (int r = 0; r < rowItems.size(); r++) {
            Item it = rowItems.get(r);
            Button b = rowButtons.get(r);
            int y = b.getY();
            String cur = idx == null ? Styles.VANILLA : Styles.current(idx, it);
            Style curStyle = idx == null ? null : Styles.find(idx, it, cur);
            PreviewCache.Tex icon = styleIcon(it, curStyle);
            g.fill(15, y + 3, 15 + 18, y + 21, Theme.PANEL_DARK);
            if (icon != null) PreviewCache.draw(g, icon, 16, y + 4, 16);
            int textW = sideW - 4 - 40 - 22;
            Theme.text(g, font, Component.translatable(it.nameKey()), 38, y + 3, textW, Theme.TEXT);
            String curName = idx == null ? "Vanilla" : currentName(idx, it);
            Theme.text(g, font, Component.literal(curName), 38, y + 13, textW,
                    cur.equals(Styles.VANILLA) ? Theme.DIM : Theme.ACCENT);
            String count = idx == null ? "–" : String.valueOf(Styles.styles(idx, it).size());
            g.drawString(font, count, 10 + sideW - 8 - font.width(count), y + 8, Theme.MUTED);
        }
        if (visibleItems.size() > rowsVisible()) {
            Theme.centered(g, font, Component.literal((listScroll + 1) + "–"
                            + Math.min(visibleItems.size(), listScroll + rowsVisible()) + " / " + visibleItems.size()),
                    8 + sideW / 2, listBottom + 2, sideW - 10, Theme.DIM);
        }

        // Kopf des Hauptbereichs
        Item it = item();
        if (it == null) return;
        int realCount = idx == null ? 0 : Styles.styles(idx, it).size();
        Theme.heading(g, font, Component.translatable(it.nameKey()), mainX + 8, top + 5, mainW / 2);
        String cur = idx == null ? "Vanilla" : currentName(idx, it);
        Theme.text(g, font, Component.translatable("tpb.items.subtitle", realCount, cur),
                mainX + 8, top + 15, mainW / 2, Theme.MUTED);
        if (Selection.dirty) {
            Theme.text(g, font, Component.translatable("tpb.items.unapplied"),
                    mainX + 8 + Math.min(mainW / 2, font.width(Component.translatable("tpb.items.subtitle", realCount, cur))) + 10,
                    top + 15, 140, Theme.WARN);
        }

        // Karten
        String chosenKey = idx == null ? Styles.VANILLA : Styles.current(idx, it);
        long now = System.currentTimeMillis();
        int star = 0;
        for (int i = 0; i < cardButtons.size(); i++) {
            Button b = cardButtons.get(i);
            Style s = cardStyles.get(i);
            int x = b.getX(), y = b.getY();
            boolean selected = s == null ? chosenKey.equals(Styles.VANILLA) : s.key().equals(chosenKey);

            int ix = x + (CARD_W - ICON) / 2, iy = y + 8;
            PreviewCache.Tex t = styleIcon(it, s);
            if (t != null) PreviewCache.draw(g, t, ix, iy, ICON);

            String name = s == null ? "Vanilla" : s.name();
            if (s != null && s.packs().size() > 1) name += " +" + (s.packs().size() - 1);
            Theme.centered(g, font, Component.literal(name), x + CARD_W / 2, y + CARD_H - 13, CARD_W - 6, Theme.TEXT);

            // Abzeichen
            int bx = x + 3;
            if (s != null && now - s.newest() < NEW_MS) bx = badge(g, Component.translatable("tpb.badge.new"), bx, y + 3, 0xFF2F7FD8);
            if (s != null && s.has3d()) badge(g, Component.literal("3D"), bx, y + 3, 0xFF8A4FD8);
            if (s != null) {
                Button sb = starButtons.get(star++);
                boolean fav = s.favorite();
                g.drawString(font, fav ? "★" : "☆", sb.getX() + 2, sb.getY() + 2, fav ? Theme.BADGE : Theme.DIM);
            }
            if (selected) {
                Theme.outline(g, x, y, CARD_W, CARD_H, Theme.ACCENT);
                Theme.outline(g, x + 1, y + 1, CARD_W - 2, CARD_H - 2, Theme.ACCENT);
                g.fill(x + CARD_W - 9, y + CARD_H - 23, x + CARD_W - 4, y + CARD_H - 18, Theme.ACCENT);
            }
            if (b.isHovered() && s != null) {
                Component info = Component.translatable("tpb.items.hover", s.textures().size(),
                        String.join(", ", s.packs().stream().map(SourcePack::name).toList()));
                Theme.text(g, font, info, mainX + 8, gridBottom + 8, mainW - 60, Theme.MUTED);
            }
        }

        if (idx == null) {
            Theme.centered(g, font, Component.translatable("tpb.grid.noscan"), mainX + mainW / 2, gridTop + 40, mainW - 20, Theme.DIM);
        } else if (realCount < 3 && fetchItemBtn.visible) {
            Theme.centered(g, font, Component.translatable("tpb.items.few", Component.translatable(it.nameKey())),
                    mainX + mainW / 2, gridBottom - 44, mainW - 20, Theme.DIM);
        }
        boolean hoveringCard = false;
        for (Button b : cardButtons) if (b.isHovered()) hoveringCard = true;
        if (!hoveringCard) {
            Theme.centered(g, font, Component.translatable("tpb.grid.page", page + 1, pages()),
                    mainX + mainW / 2, gridBottom + 8, mainW - 80, Theme.MUTED);
        }
    }

    private int badge(GuiGraphics g, Component text, int x, int y, int color) {
        int w = font.width(text) + 4;
        g.fill(x, y, x + w, y + 10, color);
        g.drawString(font, text, x + 2, y + 1, Theme.TEXT);
        return x + w + 2;
    }

    // ---------- Eingabe ----------

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mx, my, scrollX, scrollY);
        if (Theme.inside(mx, my, 8, listTop, sideW, listBottom - listTop)) {
            listScroll += scrollY > 0 ? -1 : 1;
            layoutRows();
            return true;
        }
        if (Theme.inside(mx, my, mainX, gridTop, mainW, gridBottom - gridTop)) {
            turnPage(scrollY > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
