package de.texturepackbuilder.gui;

import de.texturepackbuilder.TexturePackBuilder;
import de.texturepackbuilder.core.Exporter;
import de.texturepackbuilder.core.Importer;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Scanner;
import de.texturepackbuilder.core.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Gemeinsamer Zustand aller Bildschirme: Scannen, Bauen, Importieren, Statuszeile. */
final class Session {
    static Component status = Component.translatable("tpb.status.ready");
    static boolean busy = false;
    static boolean needsRescan = false;

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
            if (needsRescan) scan();
        }));
    }

    static void build(String packName) {
        TextureIndex idx = index();
        if (idx == null || busy) return;
        if (Selection.chosen.isEmpty()) {
            status = Component.translatable("tpb.status.nothing_selected");
            return;
        }
        busy = true;
        status = Component.translatable("tpb.status.building");
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return Exporter.export(idx, Selection.chosen, packName);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, TexturePackBuilder.EXEC).whenComplete((res, err) -> mc.execute(() -> {
            busy = false;
            if (err != null) {
                status = Component.translatable("tpb.status.build_error", reason(err));
                TexturePackBuilder.LOG.error("Export fehlgeschlagen", err);
            } else {
                status = Component.translatable("tpb.status.built", res.textures(), res.file().getFileName().toString());
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
            status = Component.translatable("tpb.status.deleted", pack.name());
            scan();
        } catch (IOException e) {
            status = Component.translatable("tpb.status.delete_error", reason(e));
        }
        refreshScreen();
    }
}
