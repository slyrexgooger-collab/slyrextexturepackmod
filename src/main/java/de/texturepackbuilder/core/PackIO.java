package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.SourcePack;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Liest Dateien aus Packs. Hält ZIP-Dateien offen, bis close() aufgerufen wird. */
public final class PackIO implements AutoCloseable {
    private final Map<Path, ZipFile> zips = new HashMap<>();

    private ZipFile zip(Path p) throws IOException {
        ZipFile z = zips.get(p);
        if (z == null) {
            z = new ZipFile(p.toFile());
            zips.put(p, z);
        }
        return z;
    }

    public byte[] read(SourcePack pack, String entry) throws IOException {
        if (pack.zip()) {
            ZipFile z = zip(pack.location());
            ZipEntry e = z.getEntry(pack.root() + entry);
            if (e == null) throw new NoSuchFileException(entry);
            try (InputStream in = z.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
        return Files.readAllBytes(pack.location().resolve(entry));
    }

    public List<String> list(SourcePack pack) throws IOException {
        List<String> out = new ArrayList<>();
        if (pack.zip()) {
            String root = pack.root();
            zip(pack.location()).stream().filter(e -> !e.isDirectory() && e.getName().startsWith(root))
                    .forEach(e -> out.add(e.getName().substring(root.length())));
        } else {
            Path root = pack.location();
            try (Stream<Path> s = Files.walk(root)) {
                s.filter(Files::isRegularFile)
                        .forEach(p -> out.add(root.relativize(p).toString().replace('\\', '/')));
            }
        }
        return out;
    }

    /** Ordner im ZIP, in dem pack.mcmeta liegt ("" = Wurzel, null = kein Resource Pack). */
    public static String findRoot(ZipFile z) {
        String best = null;
        java.util.Enumeration<? extends ZipEntry> en = z.entries();
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

    public static byte[] readOnce(SourcePack pack, String entry) throws IOException {
        try (PackIO io = new PackIO()) {
            return io.read(pack, entry);
        }
    }

    /** Liest die originale Minecraft-Textur zu einem Pfad wie "assets/minecraft/textures/block/stone.png". */
    public static byte[] readVanilla(String path) {
        String[] parts = path.split("/", 3);
        if (parts.length < 3 || !parts[0].equals("assets")) return null;
        try {
            Identifier id = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
            IoSupplier<InputStream> s = Minecraft.getInstance().getVanillaPackResources()
                    .getResource(PackType.CLIENT_RESOURCES, id);
            if (s == null) return null;
            try (InputStream in = s.get()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            return null; // ungültiger Pfad oder keine Vanilla-Textur
        }
    }

    @Override
    public void close() {
        for (ZipFile z : zips.values()) {
            try {
                z.close();
            } catch (IOException ignored) {
            }
        }
        zips.clear();
    }
}
