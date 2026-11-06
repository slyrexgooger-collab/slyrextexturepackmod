package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.SourceRef;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Baut aus der Auswahl ein fertiges Resource Pack im resourcepacks-Ordner.
 * Das Pack wird als Ordner gespeichert: Minecraft sperrt Ordner-Packs nicht, daher kann es
 * auch neu gebaut werden, während es aktiv ist (wichtig für "Im Spiel anwenden").
 */
public final class Exporter {
    /** Resource-Pack-Format von Minecraft 1.21.11. */
    private static final int PACK_FORMAT = 75;

    /** folderName = Name des Ordners in resourcepacks (Pack-ID ist "file/" + folderName). */
    public record Result(Path dir, String folderName, int textures) {}

    private Exporter() {}

    public static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "TPB Custom Pack" : s;
    }

    /** Ordner wählen: einen eigenen, früher gebauten Ordner überschreiben, nie ein fremdes Pack. */
    private static Path target(Path dir, String base) {
        Path p = dir.resolve(base);
        for (int i = 2; Files.exists(p) && !Files.exists(p.resolve(Dirs.GENERATED_MARKER)); i++) {
            p = dir.resolve(base + " (" + i + ")");
        }
        return p;
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path f : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(f);
        }
    }

    public static Result export(TextureIndex idx, Map<String, String> selection, Map<String, String> models, String packName) throws IOException {
        Path dir = Dirs.resourcepacks();
        Files.createDirectories(dir);
        Path out = target(dir, safeFileName(packName));
        deleteTree(out);
        Files.createDirectories(out);

        TreeSet<String> usedPacks = new TreeSet<>();
        TreeSet<String> written = new TreeSet<>();
        int count = 0;
        byte[] icon = null;
        try (PackIO io = new PackIO()) {
            for (Map.Entry<String, String> e : selection.entrySet()) {
                TextureEntry te = idx.entries.get(e.getKey());
                if (te == null) continue;
                Variant v = te.find(e.getValue());
                if (v == null) continue; // Quell-Pack wurde entfernt
                SourceRef r = v.primary();
                write(out, e.getKey(), io.read(r.pack(), r.entry()));
                if (r.mcmetaEntry() != null) write(out, e.getKey() + ".mcmeta", io.read(r.pack(), r.mcmetaEntry()));
                if (icon == null) {
                    try {
                        icon = io.read(r.pack(), "pack.png");
                    } catch (IOException ignored) {
                        icon = new byte[0];
                    }
                }
                usedPacks.add(r.pack().name());
                written.add(e.getKey());
                count++;
            }
            // 3D-Modelle der gewählten Styles mitkopieren
            for (Map.Entry<String, String> m : models.entrySet()) {
                Model.SourcePack pack = idx.packByName(m.getValue());
                Model.Bundle bundle = pack == null ? null : idx.bundles.getOrDefault(m.getKey(), Map.of()).get(pack);
                if (bundle == null) continue;
                for (String f : bundle.files()) {
                    try {
                        write(out, f, io.read(pack, f));
                        written.add(f);
                    } catch (IOException ignored) {
                    }
                }
                usedPacks.add(pack.name());
            }
        }
        String desc = packName.replace("\\", "\\\\").replace("\"", "\\\"");
        String mcmeta = "{\n  \"pack\": {\n"
                + "    \"description\": \"" + desc + " · " + count + " Texturen/textures\",\n"
                + "    \"min_format\": [" + PACK_FORMAT + ", 0],\n"
                + "    \"max_format\": [" + PACK_FORMAT + ", 0]\n  }\n}\n";
        write(out, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
        if (icon != null && icon.length > 0) write(out, "pack.png", icon);

        StringBuilder credits = new StringBuilder(
                "Zusammengestellt mit Texture Pack Builder / Built with Texture Pack Builder.\n"
                + "Texturen aus / Textures from (Lizenzen beachten / respect the licenses):\n\n");
        usedPacks.forEach(p -> credits.append("- ").append(p).append('\n'));
        write(out, "credits.txt", credits.toString().getBytes(StandardCharsets.UTF_8));
        write(out, Dirs.GENERATED_MARKER, String.join("\n", written).getBytes(StandardCharsets.UTF_8));
        return new Result(out, out.getFileName().toString(), count);
    }

    private static void write(Path root, String name, byte[] data) throws IOException {
        Path f = root.resolve(name);
        Files.createDirectories(f.getParent());
        Files.write(f, data);
    }
}
