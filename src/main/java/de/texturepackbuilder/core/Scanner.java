package de.texturepackbuilder.core;

import de.texturepackbuilder.TexturePackBuilder;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liest alle Texturen aus allen Packs ein und sortiert Duplikate aus:
 * - gleiche Textur mit identischen Pixeln in mehreren Packs → nur eine Variante
 * - Textur identisch mit Vanilla → wird gar nicht erst angezeigt
 */
public final class Scanner {
    private static final Pattern TEXTURE = Pattern.compile("^assets/[a-z0-9_.-]+/textures/.+\\.png$");

    @FunctionalInterface
    public interface Progress {
        void update(String packName, int current, int total);
    }

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
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String file = p.getFileName().toString();
                if (Files.isRegularFile(p) && file.toLowerCase().endsWith(".zip")) {
                    out.add(new SourcePack(file.substring(0, file.length() - 4), p, true, origin));
                } else if (Files.isDirectory(p) && Files.exists(p.resolve("pack.mcmeta"))) {
                    out.add(new SourcePack(file, p, false, origin));
                }
            }
        }
    }

    public static TextureIndex scan(Progress progress) throws IOException {
        List<SourcePack> found = findPacks();
        List<SourcePack> packs = new ArrayList<>();
        Map<String, Optional<String>> vanillaHashes = new HashMap<>();
        TextureIndex idx = new TextureIndex(packs);

        int n = 0;
        for (SourcePack pack : found) {
            n++;
            progress.update(pack.name(), n, found.size());
            try (PackIO io = new PackIO()) {
                List<String> names = io.list(pack);
                Set<String> nameSet = new HashSet<>(names);
                if (nameSet.contains(Dirs.GENERATED_MARKER)) continue; // selbst gebautes Pack überspringen
                packs.add(pack);
                for (String entry : names) {
                    if (!TEXTURE.matcher(entry).matches()) continue;
                    idx.filesScanned++;

                    ImageHash.Info info;
                    try {
                        info = ImageHash.of(io.read(pack, entry));
                    } catch (IOException e) {
                        info = null;
                    }
                    if (info == null) {
                        idx.unreadable++;
                        continue;
                    }

                    String mcmeta = nameSet.contains(entry + ".mcmeta") ? entry + ".mcmeta" : null;
                    String vanilla = vanillaHashes.computeIfAbsent(entry, k ->
                            Optional.ofNullable(ImageHash.of(PackIO.readVanilla(k))).map(ImageHash.Info::hash)
                    ).orElse(null);
                    if (mcmeta == null && info.hash().equals(vanilla)) {
                        idx.sameAsVanilla++;
                        continue;
                    }

                    TextureEntry te = idx.entries.computeIfAbsent(entry, TextureEntry::new);
                    Variant v = te.find(info.hash());
                    if (v == null) {
                        v = new Variant(info.hash(), info.width(), info.height());
                        te.variants.add(v);
                    } else {
                        idx.duplicatesRemoved++;
                    }
                    v.sources.add(new SourceRef(pack, entry, mcmeta));
                }
            } catch (IOException e) {
                TexturePackBuilder.LOG.warn("Pack {} konnte nicht gelesen werden", pack.name(), e);
            }
        }
        return idx;
    }
}
