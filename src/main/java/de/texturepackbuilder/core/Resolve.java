package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.Bundle;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.SourceRef;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.PvpCatalog.Item;
import de.texturepackbuilder.core.Selection.Pick;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Übersetzt die Auswahl in konkrete Dateien – bei jedem Aufruf neu gegen den aktuellen Scan.
 * Dadurch funktioniert die Auswahl auch nach Pack-Updates, Neu-Scans und Umbenennungen weiter.
 * Reihenfolge: Einzel-Auswahl (Tab "Einzeln") schlägt Item-Style schlägt Vanilla.
 */
public final class Resolve {
    /** Eine Datei, die ins fertige Pack kopiert wird. entry ist relativ zur Pack-Wurzel. */
    public record Src(SourcePack pack, String entry) {}

    private Resolve() {}

    /** Das Pack, das für diesen Pfad bevorzugt wird (Einzel-Auswahl, sonst Item-Style), oder null. */
    private static SourcePack preferredPack(TextureIndex idx, String path) {
        Pick p = Selection.textures.get(path);
        if (p != null && !p.vanilla()) {
            SourcePack sp = idx.packById(p.pack());
            if (sp != null) return sp;
        }
        Item it = PvpCatalog.itemFor(path);
        return it == null ? null : idx.packById(Selection.items.get(it.id()));
    }

    /** Welche Variante gilt für diesen Pfad? null = Vanilla. */
    public static Variant variant(TextureIndex idx, String path) {
        if (idx == null) return null;
        TextureEntry te = idx.entries.get(path);
        Pick p = Selection.textures.get(path);
        if (p != null) {
            if (p.vanilla() || te == null) return null;
            SourcePack sp = idx.packById(p.pack());
            for (Variant v : te.variants) if (v.hasPack(sp)) return v;
            Variant byHash = te.find(p.hash());
            if (byHash != null) return byHash;
            // Pack weg und Bild geändert → auf Item-Style zurückfallen
        }
        if (te == null) return null;
        Item it = PvpCatalog.itemFor(path);
        if (it == null) return null;
        SourcePack sp = idx.packById(Selection.items.get(it.id()));
        if (sp == null) return null;
        for (Variant v : te.variants) if (v.hasPack(sp)) return v;
        return null;
    }

    public static boolean isCustom(TextureIndex idx, String path) {
        return variant(idx, path) != null;
    }

    /** Alle Dateien des fertigen Packs: Zielpfad → Quelle. */
    public static Map<String, Src> plan(TextureIndex idx) {
        Map<String, Src> out = new TreeMap<>();
        if (idx == null) return out;
        Set<String> paths = new TreeSet<>(Selection.textures.keySet());

        // 1) Item-Styles: 3D-Modelle und alle Texturen des Items aus dem gewählten Pack
        for (Map.Entry<String, String> e : Selection.items.entrySet()) {
            Item it = PvpCatalog.byId(e.getKey());
            SourcePack sp = idx.packById(e.getValue());
            if (it == null || sp == null) continue;
            Bundle b = idx.bundles.getOrDefault(it.id(), Map.of()).get(sp);
            if (b != null) for (String f : b.files()) out.put(f, new Src(sp, f));
            for (TextureEntry te : Styles.entries(idx, it)) paths.add(te.path);
        }

        // 2) Texturen (Einzel-Auswahl überschreibt Item-Style)
        for (String path : paths) {
            Variant v = variant(idx, path);
            if (v == null) {
                Pick p = Selection.textures.get(path);
                if (p != null && p.vanilla()) {
                    out.remove(path);
                    out.remove(path + ".mcmeta");
                }
                continue;
            }
            SourceRef r = v.refFor(preferredPack(idx, path));
            if (r == null) r = v.primary();
            out.put(path, new Src(r.pack(), r.entry()));
            if (r.mcmetaEntry() != null) out.put(path + ".mcmeta", new Src(r.pack(), r.mcmetaEntry()));
            else out.remove(path + ".mcmeta");
        }
        return out;
    }
}
