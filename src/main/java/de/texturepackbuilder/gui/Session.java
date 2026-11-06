package de.texturepackbuilder.gui;

import de.texturepackbuilder.TexturePackBuilder;
import de.texturepackbuilder.core.Exporter;
import de.texturepackbuilder.core.Importer;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.ModrinthClient;
import de.texturepackbuilder.core.Resolve;
import de.texturepackbuilder.core.PvpCatalog.Item;
import de.texturepackbuilder.core.Scanner;
import de.texturepackbuilder.core.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.PackRepository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Gemeinsamer Zustand aller Bildschirme: Scannen, Bauen, Anwenden, Importieren, PvP-Download, Statuszeile. */
final class Session {
    /** Größte Datei, die der automatische PvP-Download lädt. */
    private static final long MAX_AUTO_BYTES = 40L * 1024 * 1024;

    static Component status = Component.translatable("tpb.status.ready");
    static boolean busy = false;
    static boolean fetching = false;
    static boolean needsRescan = false;
    /** "Anwenden" wurde gedrückt, während eingelesen wurde → danach automatisch ausführen. */
    private static String pendingApply = null;

    private Session() {}

    static TextureIndex index() {
        return TextureIndex.current;
    }

    private static void refreshScreen() {
        if (Minecraft.getInstance().screen instanceof BaseScreen s) s.onDataChanged();
    }

    private static String reason(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    static void scanIfNeeded() {
        if ((index() == null || needsRescan) && !busy) scan();
    }

    static void scan() {
        if (busy) {
            needsRescan = true;
            return;
        }
        busy = true;
        needsRescan = false;
        status = Component.translatable("tpb.status.scanning_start");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Scanner.scan((name, i, n) -> mc.execute(() ->
                        status = Component.translatable("tpb.status.scanning", name, i, n)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, TexturePackBuilder.EXEC).whenComplete((idx, err) -> mc.execute(() -> {
            busy = false;
            if (err != null) {
                status = Component.translatable("tpb.status.scan_error", reason(err));
                TexturePackBuilder.LOG.error("Scan fehlgeschlagen", err);
            } else {
                TextureIndex.current = idx;
                status = idx.packs.isEmpty()
                        ? Component.translatable("tpb.status.no_packs")
                        : Component.translatable("tpb.status.scan_done", idx.packs.size(), idx.entries.size(),
                        idx.duplicatesRemoved, idx.sameAsVanilla);
            }
            refreshScreen();
            if (needsRescan) {
                scan();
            } else if (pendingApply != null) {
                String name = pendingApply;
                pendingApply = null;
                build(name, true);
            }
        }));
    }

    /** Baut das Pack. Mit enable=true wird es danach aktiviert und Minecraft lädt die Ressourcen neu. */
    static void build(String packName, boolean enable) {
        TextureIndex idx = index();
        if (idx == null || busy) {
            // nicht verwerfen, sondern nach dem Einlesen nachholen
            if (enable) pendingApply = packName;
            status = Component.translatable("tpb.status.apply_queued");
            return;
        }
        if (Selection.isEmpty() && !hasOwnPackEnabled(packName)) {
            status = Component.translatable("tpb.status.nothing_selected");
            return;
        }
        busy = true;
        status = Component.translatable("tpb.status.building");
        Minecraft mc = Minecraft.getInstance();
        Map<String, Resolve.Src> plan = Resolve.plan(idx); // auf dem Hauptthread gegen den aktuellen Scan auflösen
        CompletableFuture.supplyAsync(() -> {
            try {
                return Exporter.export(plan, packName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, TexturePackBuilder.EXEC).whenComplete((res, err) -> mc.execute(() -> {
            busy = false;
            if (err != null) {
                status = Component.translatable("tpb.status.build_error", reason(err));
                TexturePackBuilder.LOG.error("Export fehlgeschlagen", err);
            } else if (enable) {
                enablePack(res.folderName(), res.textures());
            } else {
                status = Component.translatable("tpb.status.built", res.textures(), res.folderName());
            }
            refreshScreen();
            if (pendingApply != null) { // während des Bauens erneut gewählt → mit neuer Auswahl nochmal
                String name = pendingApply;
                pendingApply = null;
                build(name, true);
            }
        }));
    }

    /** Ist unser gebautes Pack aktiv? Dann muss auch eine leere Auswahl gebaut werden (= alles zurück auf Vanilla). */
    private static boolean hasOwnPackEnabled(String packName) {
        try {
            return Minecraft.getInstance().getResourcePackRepository().getSelectedIds()
                    .contains("file/" + Exporter.safeFileName(packName));
        } catch (Exception e) {
            return false;
        }
    }

    /** Aktiviert das gebaute Pack ganz oben in der Pack-Liste und lädt die Ressourcen neu. */
    private static void enablePack(String folder, int textures) {
        Minecraft mc = Minecraft.getInstance();
        try {
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            String id = "file/" + folder;
            if (repo.getPack(id) == null) {
                status = Component.translatable("tpb.status.enable_error", folder);
                return;
            }
            List<String> ids = new ArrayList<>(repo.getSelectedIds());
            ids.remove(id);
            ids.add(id); // zuletzt = höchste Priorität
            repo.setSelected(ids);
            mc.options.updateResourcePacks(repo);
            Selection.dirty = false;
            status = Component.translatable("tpb.status.applied", textures, folder);
        } catch (Exception e) {
            TexturePackBuilder.LOG.error("Pack konnte nicht aktiviert werden", e);
            status = Component.translatable("tpb.status.enable_error", folder);
        }
    }

    /**
     * Holt automatisch PvP-Packs von Modrinth für die angegebenen Items
     * (pro Item die beliebtesten passenden Packs für 1.21.11, max. 40 MB pro Datei).
     */
    static void fetchPvpPacks(List<Item> items, int perItem) {
        if (fetching || items.isEmpty()) return;
        fetching = true;
        status = Component.translatable("tpb.status.fetch_search");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            Map<String, ModrinthClient.Hit> todo = new LinkedHashMap<>();
            Set<String> have = ModrinthClient.listDownloads();
            for (Item it : items) {
                for (String term : it.search()) {
                    try {
                        int added = 0;
                        for (ModrinthClient.Hit h : ModrinthClient.search(term, "downloads", null, 0, perItem + 2).join().hits()) {
                            if (added >= perItem) break;
                            if (ModrinthClient.isDownloaded(h, have) || todo.containsKey(h.id())) continue;
                            todo.put(h.id(), h);
                            added++;
                        }
                        if (added > 0) break;
                    } catch (Exception e) {
                        TexturePackBuilder.LOG.warn("Suche nach {} fehlgeschlagen", term, e);
                    }
                }
            }
            int i = 0, ok = 0, skipped = 0;
            for (ModrinthClient.Hit h : todo.values()) {
                i++;
                int n = i, total = todo.size();
                mc.execute(() -> status = Component.translatable("tpb.status.fetch_progress", n, total, h.title()));
                try {
                    ModrinthClient.download(h, MAX_AUTO_BYTES).join();
                    ok++;
                } catch (Exception e) {
                    skipped++;
                }
            }
            int okF = ok, skippedF = skipped;
            mc.execute(() -> {
                fetching = false;
                status = Component.translatable("tpb.status.fetch_done", okF, skippedF);
                if (okF > 0) scan();
                refreshScreen();
            });
        }, TexturePackBuilder.EXEC).exceptionally(err -> {
            mc.execute(() -> {
                fetching = false;
                status = Component.translatable("tpb.status.import_error", reason(err));
            });
            return null;
        });
    }

    /** Prüft alle Modrinth-Packs auf neue Versionen. Die Auswahl bleibt dabei erhalten. */
    static void updatePacks() {
        if (fetching) return;
        fetching = true;
        status = Component.translatable("tpb.status.updates_checking");
        Minecraft mc = Minecraft.getInstance();
        ModrinthClient.updateAll().whenComplete((n, err) -> mc.execute(() -> {
            fetching = false;
            if (err != null) {
                status = Component.translatable("tpb.status.import_error", reason(err));
            } else if (n == 0) {
                status = Component.translatable("tpb.status.updates_none");
            } else {
                status = Component.translatable("tpb.status.updates_done", n);
                scan();
            }
            refreshScreen();
        }));
    }

    static void importFiles(List<Path> files) {
        if (files.isEmpty()) return;
        status = Component.translatable("tpb.status.importing");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Importer.importAll(files);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, TexturePackBuilder.EXEC).whenComplete((res, err) -> mc.execute(() -> {
            if (err != null) {
                status = Component.translatable("tpb.status.import_error", reason(err));
            } else if (res.imported().isEmpty()) {
                status = Component.translatable("tpb.status.import_none");
            } else {
                status = Component.translatable("tpb.status.imported", res.imported().size(), String.join(", ", res.imported()));
                if (!res.rejected().isEmpty()) {
                    status = status.copy().append(" ").append(Component.translatable("tpb.status.import_skipped", res.rejected().size()));
                }
                scan();
            }
            refreshScreen();
        }));
    }

    static void delete(SourcePack pack) {
        if (!pack.deletable()) return;
        try {
            Files.deleteIfExists(pack.location());
            de.texturepackbuilder.core.DownloadIndex.remove(pack.location().getFileName().toString());
            status = Component.translatable("tpb.status.deleted", pack.name());
            scan();
        } catch (IOException e) {
            status = Component.translatable("tpb.status.delete_error", reason(e));
        }
        refreshScreen();
    }
}
