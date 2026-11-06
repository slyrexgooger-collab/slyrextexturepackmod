package de.texturepackbuilder.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Datenmodell: Packs, Texturen und deren (duplikatfreie) Varianten. */
public final class Model {
    private Model() {}

    /** Woher ein Pack kommt. */
    public enum Origin {
        LOCAL("tpb.origin.local", 0xFFA8A8A8),
        MODRINTH("tpb.origin.modrinth", 0xFF1BD96A),
        IMPORT("tpb.origin.import", 0xFF5AB4FF);

        public final String key;
        public final int color;

        Origin(String key, int color) {
            this.key = key;
            this.color = color;
        }
    }

    /** Ein Quell-Pack: ZIP-Datei oder entpackter Ordner. */
    public record SourcePack(String name, Path location, boolean zip, Origin origin) {
        /** Nur Packs, die die Mod selbst angelegt hat, dürfen im Spiel gelöscht werden. */
        public boolean deletable() {
            return zip && origin != Origin.LOCAL;
        }
    }

    /** Wo genau eine Variante in einem Pack liegt (plus optionale .mcmeta für Animationen). */
    public record SourceRef(SourcePack pack, String entry, String mcmetaEntry) {}

    /**
     * Eine eindeutige Version einer Textur. Gleiche Bilder (gleiche Pixel) aus
     * verschiedenen Packs landen in derselben Variante – so werden Duplikate aussortiert.
     */
    public static final class Variant {
        public final String hash;
        public final int width;
        public final int height;
        public final List<SourceRef> sources = new ArrayList<>();

        public Variant(String hash, int width, int height) {
            this.hash = hash;
            this.width = width;
            this.height = height;
        }

        public SourceRef primary() {
            return sources.get(0);
        }

        public boolean animated() {
            return primary().mcmetaEntry() != null;
        }

        public boolean hasPack(SourcePack p) {
            for (SourceRef r : sources) if (r.pack().equals(p)) return true;
            return false;
        }

        public String sourceNames() {
            Set<String> names = new LinkedHashSet<>();
            for (SourceRef r : sources) names.add(r.pack().name());
            return String.join(", ", names);
        }
    }

    /** Eine Textur (z. B. assets/minecraft/textures/block/stone.png) mit allen Varianten. */
    public static final class TextureEntry {
        public final String path;
        public final List<Variant> variants = new ArrayList<>();

        public TextureEntry(String path) {
            this.path = path;
        }

        public Variant find(String hash) {
            if (hash == null) return null;
            for (Variant v : variants) if (v.hash.equals(hash)) return v;
            return null;
        }
    }

    /** Ergebnis eines Scans über alle Packs. */
    public static final class TextureIndex {
        public static volatile TextureIndex current;

        public final List<SourcePack> packs;
        public final Map<String, TextureEntry> entries = new TreeMap<>();
        public int filesScanned;
        public int duplicatesRemoved;
        public int sameAsVanilla;
        public int unreadable;

        public TextureIndex(List<SourcePack> packs) {
            this.packs = packs;
        }

        /** Anzahl eindeutiger Texturen, die ein Pack beisteuert. */
        public int countFor(SourcePack p) {
            int n = 0;
            for (TextureEntry e : entries.values())
                for (Variant v : e.variants)
                    if (v.hasPack(p)) {
                        n++;
                        break;
                    }
            return n;
        }
    }
}
