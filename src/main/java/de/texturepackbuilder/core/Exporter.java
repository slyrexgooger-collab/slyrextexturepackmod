package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.SourceRef;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Baut aus der Auswahl ein fertiges Resource Pack im resourcepacks-Ordner. */
public final class Exporter {
    /** Resource-Pack-Format von Minecraft 1.21.11. */
    private static final int PACK_FORMAT = 75;

    public record Result(Path file, int textures) {}

    private Exporter() {}

    public static String safeFileName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "TPB Custom Pack" : s;
    }

    public static Result export(TextureIndex idx, Map<String, String> selection, String packName) throws IOException {
        Path dir = Dirs.resourcepacks();
        Files.createDirectories(dir);
        String base = safeFileName(packName);
        Path tmp = dir.resolve(base + ".zip.tmp");
        TreeSet<String> usedPacks = new TreeSet<>();
        TreeSet<String> written = new TreeSet<>();
        int count = 0;
        byte[] icon = null;

        try (PackIO io = new PackIO(); ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (Map.Entry<String, String> e : selection.entrySet()) {
                TextureEntry te = idx.entries.get(e.getKey());
                if (te == null) continue;
                Variant v = te.find(e.getValue());
                if (v == null) continue; // Quell-Pack wurde entfernt
                SourceRef r = v.primary();
                put(zos, e.getKey(), io.read(r.pack(), r.entry()));
                if (r.mcmetaEntry() != null) put(zos, e.getKey() + ".mcmeta", io.read(r.pack(), r.mcmetaEntry()));
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
            String desc = packName.replace("\\", "\\\\").replace("\"", "\\\"");
            String mcmeta = "{\n  \"pack\": {\n"
                    + "    \"description\": \"" + desc + " · " + count + " Texturen/textures\",\n"
                    + "    \"min_format\": [" + PACK_FORMAT + ", 0],\n"
                    + "    \"max_format\": [" + PACK_FORMAT + ", 0]\n  }\n}\n";
            put(zos, "pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));
            if (icon != null && icon.length > 0) put(zos, "pack.png", icon);

            StringBuilder credits = new StringBuilder(
                    "Zusammengestellt mit Texture Pack Builder / Built with Texture Pack Builder.\n"
                    + "Texturen aus / Textures from (Lizenzen beachten / respect the licenses):\n\n");
            usedPacks.forEach(p -> credits.append("- ").append(p).append('\n'));
            put(zos, "credits.txt", credits.toString().getBytes(StandardCharsets.UTF_8));
            put(zos, Dirs.GENERATED_MARKER, String.join("\n", written).getBytes(StandardCharsets.UTF_8));
        }

        Path out = dir.resolve(base + ".zip");
        try {
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException locked) {
            // Pack ist evtl. gerade aktiv und von Minecraft gesperrt (Windows) → neuer Dateiname
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
            out = dir.resolve(base + " " + stamp + ".zip");
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Result(out, count);
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }
}
