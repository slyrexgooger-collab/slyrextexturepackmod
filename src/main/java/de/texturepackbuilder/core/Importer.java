package de.texturepackbuilder.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Lädt eigene Resource Packs in die Mod hoch (Dateiauswahl oder Drag & Drop).
 * Akzeptiert ZIP-Dateien und Ordner. Packs, die in einem Unterordner verpackt sind
 * (z. B. "MeinPack/pack.mcmeta" im ZIP), werden automatisch richtig umgepackt.
 */
public final class Importer {
    public record Result(List<String> imported, List<String> rejected) {}

    private Importer() {}

    public static Result importAll(List<Path> files) throws IOException {
        Files.createDirectories(Dirs.imported());
        List<String> ok = new ArrayList<>();
        List<String> bad = new ArrayList<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            try {
                if (Files.isDirectory(f)) {
                    if (!Files.exists(f.resolve("pack.mcmeta"))) {
                        bad.add(name);
                        continue;
                    }
                    Path out = unique(name);
                    zipDirectory(f, out);
                    ok.add(stem(out));
                } else if (name.toLowerCase().endsWith(".zip")) {
                    try (ZipFile z = new ZipFile(f.toFile())) {
                        String root = findRoot(z);
                        if (root == null) {
                            bad.add(name);
                            continue;
                        }
                        Path out = unique(name.substring(0, name.length() - 4));
                        if (root.isEmpty()) Files.copy(f, out);
                        else repack(z, root, out);
                        ok.add(stem(out));
                    }
                } else {
                    bad.add(name);
                }
            } catch (ZipException e) {
                bad.add(name);
            }
        }
        return new Result(ok, bad);
    }

    /** Sucht den Ordner im ZIP, in dem pack.mcmeta liegt ("" = Wurzel, null = keins). */
    private static String findRoot(ZipFile z) {
        String best = null;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (!n.endsWith("pack.mcmeta")) continue;
            String prefix = n.substring(0, n.length() - "pack.mcmeta".length());
            if (!prefix.isEmpty() && !prefix.endsWith("/")) continue;
            if (prefix.chars().filter(c -> c == '/').count() > 2) continue;
            if (best == null || prefix.length() < best.length()) best = prefix;
        }
        return best;
    }

    private static void repack(ZipFile z, String root, Path out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out))) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().startsWith(root)) continue;
                zos.putNextEntry(new ZipEntry(e.getName().substring(root.length())));
                try (InputStream in = z.getInputStream(e)) {
                    in.transferTo(zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void zipDirectory(Path dir, Path out) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(out));
             Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                zos.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zos);
                zos.closeEntry();
            }
        }
    }

    private static Path unique(String base) {
        String clean = Exporter.safeFileName(base);
        Path p = Dirs.imported().resolve(clean + ".zip");
        for (int i = 2; Files.exists(p); i++) p = Dirs.imported().resolve(clean + " (" + i + ").zip");
        return p;
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".zip") ? n.substring(0, n.length() - 4) : n;
    }
}
