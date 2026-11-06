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
    /** Beim nächsten Anwenden komplett neu laden statt Blitzwechsel (Neu-laden-Knopf). */
    private static boolean forceFullReload = false;
    /** Zuletzt angewendeter Plan – für den Blitzwechsel wird nur der Unterschied getauscht. */
    private static Map<String, Resolve.Src> lastApplied = null;
    /** Fortschritt des PvP-Downloads (für den Balken). */
    static int progressDone = 0, progressTotal = 0;

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
        scan(false);
    }

    /** force = Zwischenspeicher ignorieren und jedes Pack komplett neu einlesen. */
    static void scan(boolean force) {
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
                        status = Component.translatable("tpb.status.scanning", name, i, n)), force);
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
                apply(res, plan);
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

    /**
     * Pack ins Spiel bringen: wenn möglich per Blitzwechsel (nur geänderte Texturen direkt auf der GPU,
     * dauert Millisekunden), sonst mit komplettem Ressourcen-Neuladen.
     */
    private static void apply(Exporter.Result res, Map<String, Resolve.Src> plan) {
        Minecraft mc = Minecraft.getInstance();
        String id = "file/" + res.folderName();
        boolean active;
        try {
            active = mc.getResourcePackRepository().getSelectedIds().contains(id);
        } catch (Exception e) {
            active = false;
        }
        if (active && !forceFullReload) {
            java.util.Set<String> changed = new java.util.TreeSet<>();
            if (lastApplied == null) {
                changed.addAll(res.previous());
                changed.addAll(plan.keySet());
            } else {
                for (Map.Entry<String, Resolve.Src> e : plan.entrySet()) {
                    if (!e.getValue().equals(lastApplied.get(e.getKey()))) changed.add(e.getKey());
                }
                for (String k : lastApplied.keySet()) if (!plan.containsKey(k)) changed.add(k);
            }
            long t0 = System.nanoTime();
            if (changed.isEmpty() || de.texturepackbuilder.core.HotSwap.trySwap(changed)) {
                lastApplied = plan;
                Selection.dirty = false;
                long ms = Math.max(1, (System.nanoTime() - t0) / 1_000_000);
                status = Component.translatable("tpb.status.swapped", changed.size(), ms);
                return;
            }
        }
        forceFullReload = false;
        enablePack(res.folderName(), res.textures());
        lastApplied = plan;
    }

    /** Neu-laden-Knopf: Packs neu einlesen, Pack neu bauen und Minecraft komplett neu laden lassen. */
    static void reloadAll(String packName) {
        forceFullReload = true;
        lastApplied = null;
        if (busy) {
            needsRescan = true;
            pendingApply = packName;
            return;
        }
        pendingApply = packName;
        scan(true);
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
    static void fetchPvpPacks(List<Item> items, int perTerm) {
        if (fetching || items.isEmpty()) return;
        fetching = true;
        progressDone = 0;
        progressTotal = 0;
        status = Component.translatable("tpb.status.fetch_search");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.runAsync(() -> {
            // 1) für jedes Item alle Suchbegriffe abfragen (so viele Packs wie möglich)
            Map<String, ModrinthClient.Hit> todo = new LinkedHashMap<>();
            Set<String> have = ModrinthClient.listDownloads();
            for (Item it : items) {
                for (String term : it.search()) {
                    try {
                        int added = 0;
                        for (ModrinthClient.Hit h : ModrinthClient.search(term, "downloads", null, 0, perTerm + 4).join().hits()) {
                            if (added >= perTerm) break;
                            if (ModrinthClient.isDownloaded(h, have) || todo.containsKey(h.id())) continue;
                            todo.put(h.id(), h);
                            added++;
                        }
                    } catch (Exception e) {
                        TexturePackBuilder.LOG.warn("Suche nach {} fehlgeschlagen", term, e);
                    }
                }
            }
            // 2) parallel herunterladen (4 gleichzeitig)
            int total = todo.size();
            mc.execute(() -> progressTotal = total);
            java.util.concurrent.atomic.AtomicInteger ok = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "TexturePackBuilder-Download");
                t.setDaemon(true);
                return t;
            });
            List<CompletableFuture<Void>> jobs = new ArrayList<>();
            for (ModrinthClient.Hit h : todo.values()) {
                jobs.add(CompletableFuture.runAsync(() -> {
                    try {
                        ModrinthClient.download(h, MAX_AUTO_BYTES).join();
                        ok.incrementAndGet();
                    } catch (Exception ignored) {
                    }
                    int n = done.incrementAndGet();
                    mc.execute(() -> {
                        progressDone = n;
                        status = Component.translatable("tpb.status.fetch_progress", n, total, h.title());
                    });
                }, pool));
            }
            CompletableFuture.allOf(jobs.toArray(new CompletableFuture[0])).join();
            pool.shutdown();
            int okF = ok.get(), skippedF = total - okF;
            mc.execute(() -> {
                fetching = false;
                progressTotal = 0;
                status = Component.translatable("tpb.status.fetch_done", okF, skippedF);
                if (okF > 0) scan();
                refreshScreen();
            });
        }, TexturePackBuilder.EXEC).exceptionally(err -> {
            mc.execute(() -> {
                fetching = false;
                progressTotal = 0;
                status = Component.translatable("tpb.status.import_error", reason(err));
            });
            return null;
        });
    }

    /** Erster Start: einmalig alle PvP-Packs für alle Items laden. */
    static void initialDownloadIfNeeded() {
        java.nio.file.Path flag = de.texturepackbuilder.core.Dirs.downloads().resolveSibling("initial_download_done");
        if (Files.exists(flag)) return;
        try {
            Files.createDirectories(flag.getParent());
            Files.writeString(flag, "1");
        } catch (IOException ignored) {
        }
        fetchPvpPacks(de.texturepackbuilder.core.PvpCatalog.ITEMS, 6);
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
