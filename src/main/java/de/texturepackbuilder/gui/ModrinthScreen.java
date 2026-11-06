package de.texturepackbuilder.gui;

import de.texturepackbuilder.TexturePackBuilder;
import de.texturepackbuilder.core.ModrinthClient;
import de.texturepackbuilder.core.ModrinthClient.Hit;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab "Modrinth": Live-Suche (startet beim Tippen), Sortierung, Auflösungsfilter,
 * Seiten, Projekt-Icons und Download mit einem Klick.
 */
public class ModrinthScreen extends BaseScreen {
    private static final int ROW = 36;

    private static String query = "";
    private static String lastSearched = null;
    private static int sortIdx = 0, resIdx = 0, offset = 0, total = 0, requestId = 0;
    private static List<Hit> hits = List.of();
    private static Component info = Component.empty();
    private static final Map<String, byte[]> icons = new ConcurrentHashMap<>();
    private static final Set<String> iconRequested = ConcurrentHashMap.newKeySet();
    private static final Set<String> downloading = ConcurrentHashMap.newKeySet();

    private int listTop, listBottom;
    private long lastEdit = 0;
    private Set<String> downloaded = Set.of();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<Hit> rowHits = new ArrayList<>();
    private Button prevBtn, nextBtn;
    private EditBox box;

    public ModrinthScreen(Screen root) {
        super(root, Tab.MODRINTH);
    }

    @Override
    protected void init() {
        super.init();
        int top = HEADER + 6;
        listTop = top + 26;
        listBottom = height - FOOTER - 28;
        int sortW = 130, resW = 110;
        int boxW = Math.max(80, width - 16 - sortW - resW - 8);

        box = addRenderableWidget(new EditBox(font, 8, top + 1, boxW, 18, Component.translatable("tpb.modrinth.hint")));
        box.setMaxLength(100);
        box.setHint(Component.translatable("tpb.modrinth.hint").withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(query);
        box.setResponder(s -> {
            query = s;
            lastEdit = Util.getMillis();
        });
        setInitialFocus(box);

        addRenderableWidget(Button.builder(sortLabel(), b -> {
            sortIdx = (sortIdx + 1) % ModrinthClient.SORTS.length;
            b.setMessage(sortLabel());
            search(true);
        }).bounds(12 + boxW, top, sortW, 20).build());
        addRenderableWidget(Button.builder(resLabel(), b -> {
            resIdx = (resIdx + 1) % ModrinthClient.RESOLUTIONS.length;
            b.setMessage(resLabel());
            search(true);
        }).bounds(16 + boxW + sortW, top, resW, 20).build());

        prevBtn = addRenderableWidget(Button.builder(Component.literal("◀"), b -> {
            offset = Math.max(0, offset - perPage());
            search(false);
        }).bounds(8, listBottom + 4, 20, 18).build());
        nextBtn = addRenderableWidget(Button.builder(Component.literal("▶"), b -> {
            offset += perPage();
            search(false);
        }).bounds(width - 28, listBottom + 4, 20, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("tpb.button.done"), b -> onClose())
                .bounds(width - 68, height - FOOTER + 4, 60, 20).build());

        downloaded = ModrinthClient.listDownloads();
        layoutRows();
        if (lastSearched == null) search(true);
    }

    private Component sortLabel() {
        return Component.translatable("tpb.modrinth.sort",
                Component.translatable("tpb.modrinth.sort." + ModrinthClient.SORTS[sortIdx]));
    }

    private Component resLabel() {
        String r = ModrinthClient.RESOLUTIONS[resIdx];
        return Component.translatable("tpb.modrinth.res", r == null ? Component.translatable("tpb.modrinth.res.any") : Component.literal(r));
    }

    private int perPage() {
        return Math.max(1, Math.min(50, (listBottom - listTop - 4) / ROW));
    }

    @Override
    public void tick() {
        super.tick();
        // Live-Suche: kurz nach dem letzten Tastendruck automatisch suchen
        if (lastEdit > 0 && !query.equals(lastSearched) && Util.getMillis() - lastEdit > 400) {
            lastEdit = 0;
            search(true);
        }
    }

    private void search(boolean reset) {
        if (reset) offset = 0;
        lastSearched = query;
        int id = ++requestId;
        info = Component.translatable("tpb.modrinth.searching");
        Minecraft mc = Minecraft.getInstance();
        ModrinthClient.search(query, ModrinthClient.SORTS[sortIdx], ModrinthClient.RESOLUTIONS[resIdx], offset, perPage())
                .whenComplete((res, err) -> mc.execute(() -> {
                    if (id != requestId) return; // veraltete Antwort
                    if (err != null) {
                        info = Component.translatable("tpb.modrinth.error", rootMessage(err));
                        hits = List.of();
                        total = 0;
                    } else {
                        hits = res.hits();
                        total = res.total();
                        info = total == 0 ? Component.translatable("tpb.modrinth.none")
                                : Component.translatable("tpb.modrinth.results", total);
                        for (Hit h : hits) requestIcon(h);
                    }
                    if (mc.screen instanceof ModrinthScreen m) m.layoutRows();
                }));
    }

    private static void requestIcon(Hit h) {
        if (h.iconUrl() == null || !iconRequested.add(h.id())) return;
        ModrinthClient.fetchBytes(h.iconUrl()).whenComplete((bytes, err) -> {
            if (bytes != null) icons.put(h.id(), bytes);
        });
    }

    private void layoutRows() {
        rowButtons.forEach(this::removeWidget);
        rowButtons.clear();
        rowHits.clear();
        int per = perPage();
        for (int i = 0; i < Math.min(per, hits.size()); i++) {
            Hit h = hits.get(i);
            int y = listTop + 4 + i * ROW + (ROW - 20) / 2 - 2;
            boolean done = ModrinthClient.isDownloaded(h.slug(), downloaded);
            boolean loading = downloading.contains(h.id());
            Component label = done ? Component.translatable("tpb.modrinth.downloaded").withStyle(ChatFormatting.GREEN)
                    : loading ? Component.translatable("tpb.modrinth.loading")
                    : Component.translatable("tpb.modrinth.download");
            Button b = addRenderableWidget(Button.builder(label, btn -> download(h))
                    .bounds(width - 14 - 90, y, 90, 20).build());
            b.active = !done && !loading;
            rowButtons.add(b);
            rowHits.add(h);
        }
        prevBtn.active = offset > 0;
        nextBtn.active = offset + per < total;
    }

    private void download(Hit h) {
        if (!downloading.add(h.id())) return;
        Session.status = Component.translatable("tpb.modrinth.downloading", h.title());
        layoutRows();
        Minecraft mc = Minecraft.getInstance();
        ModrinthClient.download(h).whenComplete((path, err) -> mc.execute(() -> {
            downloading.remove(h.id());
            if (err != null) {
                Session.status = Component.translatable("tpb.modrinth.download_error", rootMessage(err));
                TexturePackBuilder.LOG.warn("Download fehlgeschlagen", err);
            } else {
                Session.status = Component.translatable("tpb.modrinth.download_done", h.title());
                Session.needsRescan = true;
            }
            if (mc.screen instanceof ModrinthScreen m) {
                m.downloaded = ModrinthClient.listDownloads();
                m.layoutRows();
            }
        }));
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    private static String compact(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fk", n / 1_000.0);
        return String.valueOf(n);
    }

    @Override
    public void onDataChanged() {
        downloaded = ModrinthClient.listDownloads();
        layoutRows();
    }

    @Override
    protected int statusWidth() {
        return width - 84;
    }

    @Override
    protected void drawPanels(GuiGraphics g) {
        Theme.panel(g, 8, listTop, width - 16, listBottom - listTop);
        for (int i = 0; i < rowHits.size(); i++) {
            int y = listTop + 4 + i * ROW;
            if (i % 2 == 1) g.fill(9, y - 2, width - 9, y + ROW - 2, 0x20FFFFFF);
        }
    }

    @Override
    protected void drawContent(GuiGraphics g, int mouseX, int mouseY) {
        int textRight = width - 14 - 90 - 8;
        for (int i = 0; i < rowHits.size(); i++) {
            Hit h = rowHits.get(i);
            int y = listTop + 4 + i * ROW;
            int ix = 14, iy = y + 2;
            g.fill(ix, iy, ix + 30, iy + 30, Theme.PANEL_DARK);
            if (icons.containsKey(h.id())) {
                PreviewCache.Tex t = previews.get("icon|" + h.id(), () -> icons.get(h.id()));
                if (t != null) PreviewCache.draw(g, t, ix, iy, 30);
            }
            int tx = ix + 38, tw = textRight - tx;
            Theme.text(g, font, Component.literal(h.title()), tx, y + 2, tw, Theme.TEXT);
            Theme.text(g, font, Component.translatable("tpb.modrinth.by", h.author(), compact(h.downloads())),
                    tx, y + 12, tw, Theme.MUTED);
            Theme.text(g, font, Component.literal(h.description()), tx, y + 22, tw, Theme.DIM);
        }
        if (rowHits.isEmpty()) {
            Theme.centered(g, font, info, width / 2, listTop + 20, width - 40, Theme.DIM);
        }
        int pages = Math.max(1, (total + perPage() - 1) / perPage());
        Component pageInfo = Component.translatable("tpb.grid.page", offset / perPage() + 1, pages)
                .append(Component.literal("  ·  ")).append(info);
        Theme.centered(g, font, pageInfo, width / 2, listBottom + 9, width - 80, Theme.MUTED);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY != 0 && Theme.inside(mx, my, 8, listTop, width - 16, listBottom - listTop)) {
            if (scrollY < 0 && offset + perPage() < total) {
                offset += perPage();
                search(false);
            } else if (scrollY > 0 && offset > 0) {
                offset = Math.max(0, offset - perPage());
                search(false);
            }
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }
}
