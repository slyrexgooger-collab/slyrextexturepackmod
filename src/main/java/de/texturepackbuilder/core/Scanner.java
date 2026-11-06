package de.texturepackbuilder.core;

import de.texturepackbuilder.TexturePackBuilder;
import de.texturepackbuilder.core.Model.Bundle;
import de.texturepackbuilder.core.Model.Origin;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.SourceRef;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Liest alle Texturen aus allen Packs ein und sortiert Duplikate aus:
 * - gleiche Textur mit identischen Pixeln in mehreren Packs → nur eine Variante
 * - Textur identisch mit Vanilla → wird gar nicht erst angezeigt
 *
 * Schnell auch bei Hunderten Packs: Packs werden parallel gelesen, und unveränderte ZIPs
 * (gleiche Größe und Änderungszeit) kommen aus dem Zwischenspeicher.
 */
public final class Scanner {
    private static final Pattern TEXTURE = Pattern.compile("^assets/[a-z0-9_.-]+/textures/.+\\.png$");

    @FunctionalInterface
    public interface Progress {
        void update(String packName, int current, int total);
    }

    /** Ergebnis für ein einzelnes Pack, unabhängig vom Rest. */
    private record Tex(String entry, String hash, int w, int h, String mcmeta) {}

    private record PackScan(long size, long mtime, boolean generated, List<Tex> textures,
                            Map<String, Bundle> bundles, int scanned, int unreadable, int sameAsVanilla) {}

    private static final Map<Path, PackScan> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Optional<String>> VANILLA = new ConcurrentHashMap<>();

    private Scanner() {}

    public static List<SourcePack> findPacks() throws IOException {
        List<SourcePack> packs = new ArrayList<>();
        collect(Dirs.resourcepacks(), Origin.LOCAL, packs);
        collect(Dirs.imported(), Origin.IMPORT, packs);
        collect(Dirs.downloads(), Origin.MODRINTH, packs);
        return packs;
    }

    private static void collect(Path dir, Origin origin, List<SourcePack> out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Map<String, DownloadIndex.Info> downloads = origin == Origin.MODRINTH ? DownloadIndex.load() : Map.of();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) files.add(p);
        }
        files.sort(null);
        for (Path p : files) {
            String file = p.getFileName().toString();
            long modified = Files.getLastModifiedTime(p).toMillis();
            if (Files.isRegularFile(p) && file.toLowerCase().endsWith(".zip")) {
                String root;
                try (ZipFile z = new ZipFile(p.toFile())) {
                    root = PackIO.findRoot(z);
                } catch (IOException e) {
                    continue; // kaputtes ZIP
                }
                if (root == null) continue; // kein Resource Pack
                String stem = file.substring(0, file.length() - 4);
                String id, name = stem;
                switch (origin) {
                    case MODRINTH -> {
                        DownloadIndex.Info info = downloads.get(file);
                        if (info != null && !info.projectId().isEmpty()) {
                            id = "mr:" + info.projectId();
                            if (!info.title().isEmpty()) name = info.title();
                        } else {
                            id = "mr-file:" + stem;
                        }
                    }
                    case IMPORT -> id = "import:" + stem;
                    default -> id = "local:" + file;
                }
                out.add(new SourcePack(id, name, p, true, origin, modified, root));
            } else if (Files.isDirectory(p) && Files.exists(p.resolve("pack.mcmeta"))) {
                String id = (origin == Origin.LOCAL ? "local-dir:" : "dir:") + file;
                out.add(new SourcePack(id, file, p, false, origin, modified, ""));
            }
        }
    }

    public static TextureIndex scan(Progress progress) throws IOException {
        return scan(progress, false);
    }

    public static TextureIndex scan(Progress progress, boolean force) throws IOException {
        if (force) CACHE.clear();
        List<SourcePack> found = findPacks();
        AtomicInteger done = new AtomicInteger();
        // parallel einlesen, Reihenfolge der Ergebnisse bleibt erhalten
        List<PackScan> scans = found.parallelStream().map(p -> {
            PackScan s = scanPack(p);
            progress.update(p.name(), done.incrementAndGet(), found.size());
            return s;
        }).toList();

        List<SourcePack> packs = new ArrayList<>();
        TextureIndex idx = new TextureIndex(packs);
        for (int i = 0; i < found.size(); i++) {
            SourcePack pack = found.get(i);
            PackScan s = scans.get(i);
            if (s == null || s.generated()) continue; // unlesbar oder selbst gebautes Pack
            packs.add(pack);
            idx.filesScanned += s.scanned();
            idx.unreadable += s.unreadable();
            idx.sameAsVanilla += s.sameAsVanilla();
            for (Map.Entry<String, Bundle> b : s.bundles().entrySet()) {
                idx.bundles.computeIfAbsent(b.getKey(), k -> new java.util.HashMap<>()).put(pack, b.getValue());
            }
            for (Tex t : s.textures()) {
                TextureEntry te = idx.entries.computeIfAbsent(t.entry(), TextureEntry::new);
                Variant v = te.find(t.hash());
                if (v == null) {
                    v = new Variant(t.hash(), t.w(), t.h());
                    te.variants.add(v);
                } else {
                    idx.duplicatesRemoved++;
                }
                v.sources.add(new SourceRef(pack, t.entry(), t.mcmeta()));
            }
        }
        return idx;
    }

    private static PackScan scanPack(SourcePack pack) {
        long size = 0, mtime = pack.modified();
        try {
            if (pack.zip()) size = Files.size(pack.location());
        } catch (IOException ignored) {
        }
        if (pack.zip()) {
            PackScan cached = CACHE.get(pack.location());
            if (cached != null && cached.size() == size && cached.mtime() == mtime) return cached;
        }
        try (PackIO io = new PackIO()) {
            List<String> names = io.list(pack);
            Set<String> nameSet = new HashSet<>(names);
            if (nameSet.contains(Dirs.GENERATED_MARKER)) {
                return new PackScan(size, mtime, true, List.of(), Map.of(), 0, 0, 0);
            }
            Map<String, Bundle> bundles = ModelBundles.collect(pack, io, names, nameSet);
            List<Tex> textures = new ArrayList<>();
            int scanned = 0, unreadable = 0, same = 0;
            for (String entry : names) {
                if (!TEXTURE.matcher(entry).matches()) continue;
                scanned++;
                ImageHash.Info info;
                try {
                    info = ImageHash.of(io.read(pack, entry));
                } catch (IOException e) {
                    info = null;
                }
                if (info == null) {
                    unreadable++;
                    continue;
                }
                String mcmeta = nameSet.contains(entry + ".mcmeta") ? entry + ".mcmeta" : null;
                String vanilla = VANILLA.computeIfAbsent(entry, k ->
                        Optional.ofNullable(ImageHash.of(PackIO.readVanilla(k))).map(ImageHash.Info::hash)).orElse(null);
                if (mcmeta == null && info.hash().equals(vanilla)) {
                    same++;
                    continue;
                }
                textures.add(new Tex(entry, info.hash(), info.width(), info.height(), mcmeta));
            }
            PackScan s = new PackScan(size, mtime, false, textures, bundles, scanned, unreadable, same);
            if (pack.zip()) CACHE.put(pack.location(), s);
            return s;
        } catch (IOException e) {
            TexturePackBuilder.LOG.warn("Pack {} konnte nicht gelesen werden", pack.name(), e);
            return null;
        }
    }
}
