package de.texturepackbuilder.core;

import de.texturepackbuilder.core.Model.Bundle;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.Model.TextureEntry;
import de.texturepackbuilder.core.Model.TextureIndex;
import de.texturepackbuilder.core.Model.Variant;
import de.texturepackbuilder.core.PvpCatalog.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ein "Style" ist das Aussehen eines PvP-Items aus einem Pack: alle Texturen dieses
 * Items, die das Pack ändert. Packs mit exakt gleichen Texturen für ein Item werden zu
 * einem Style zusammengefasst – Duplikate erscheinen also nur einmal.
 */
public final class Styles {
    public static final String VANILLA = "vanilla";
    public static final String MIXED = "mixed";

    /** iconPath = Datei im Pack für das Vorschaubild (null = Vanilla-Icon zeigen), bundle = 3D-Modelle oder null. */
    public record Style(String key, Item item, List<SourcePack> packs, Map<String, String> textures,
                        String iconPath, Bundle bundle) {
        public boolean has3d() {
            return bundle != null;
        }

        public SourcePack pack() {
            return packs.get(0);
        }

        public String name() {
            return pack().name();
        }

        public long newest() {
            long m = 0;
            for (SourcePack p : packs) m = Math.max(m, p.modified());
            return m;
        }

        public String favoriteKey() {
            return item.id() + "|" + name();
        }
    }

    private static TextureIndex cachedFor;
    private static final Map<String, List<TextureEntry>> ENTRIES = new HashMap<>();
    private static final Map<String, List<Style>> STYLES = new HashMap<>();

    private Styles() {}

    private static void ensure(TextureIndex idx) {
        if (idx == cachedFor) return;
        cachedFor = idx;
        ENTRIES.clear();
        STYLES.clear();
        if (idx == null) return;
        for (TextureEntry e : idx.entries.values()) {
            Item it = PvpCatalog.itemFor(e.path);
            if (it != null) ENTRIES.computeIfAbsent(it.id(), k -> new ArrayList<>()).add(e);
        }
    }

    public static synchronized List<TextureEntry> entries(TextureIndex idx, Item item) {
        ensure(idx);
        return ENTRIES.getOrDefault(item.id(), List.of());
    }

    /** Alle Styles eines Items (ohne Vanilla). */
    public static synchronized List<Style> styles(TextureIndex idx, Item item) {
        ensure(idx);
        if (idx == null) return List.of();
        return STYLES.computeIfAbsent(item.id(), k -> compute(idx, item));
    }

    private static List<Style> compute(TextureIndex idx, Item item) {
        List<TextureEntry> entries = ENTRIES.getOrDefault(item.id(), List.of());
        Map<String, Style> byKey = new LinkedHashMap<>();
        for (SourcePack p : idx.packs) {
            Map<String, String> tex = new TreeMap<>();
            for (TextureEntry e : entries)
                for (Variant v : e.variants)
                    if (v.hasPack(p)) {
                        tex.put(e.path, v.hash);
                        break;
                    }
            Bundle bundle = idx.bundles.getOrDefault(item.id(), Map.of()).get(p);
            if (tex.isEmpty() && bundle == null) continue;
            String key = tex + "|" + (bundle == null ? "" : bundle.hash());
            Style existing = byKey.get(key);
            if (existing != null) {
                existing.packs().add(p);
                continue;
            }
            String iconPath = null;
            for (String c : item.iconPaths()) if (tex.containsKey(c)) {
                iconPath = c;
                break;
            }
            if (iconPath == null && !tex.isEmpty()) iconPath = tex.keySet().iterator().next();
            if (iconPath == null) {
                for (String f : bundle.files()) if (f.endsWith(".png") && f.contains("/item/")) {
                    iconPath = f;
                    break;
                }
            }
            if (iconPath == null) for (String f : bundle.files()) if (f.endsWith(".png")) {
                iconPath = f;
                break;
            }
            List<SourcePack> packs = new ArrayList<>();
            packs.add(p);
            byKey.put(key, new Style(key, item, packs, tex, iconPath, bundle));
        }
        return new ArrayList<>(byKey.values());
    }

    /** Welcher Style ist gerade gewählt? Ergebnis: VANILLA, MIXED oder der Schlüssel des Styles. */
    public static String current(TextureIndex idx, Item item) {
        Map<String, String> chosen = new TreeMap<>();
        for (TextureEntry e : entries(idx, item)) {
            String h = Selection.chosen.get(e.path);
            if (h != null) chosen.put(e.path, h);
        }
        String model = Selection.models.get(item.id());
        if (chosen.isEmpty() && model == null) return VANILLA;
        for (Style s : styles(idx, item)) {
            String wantModel = s.has3d() ? s.name() : null;
            if (s.textures().equals(chosen) && java.util.Objects.equals(model, wantModel)) return s.key();
        }
        return MIXED;
    }

    public static Style find(TextureIndex idx, Item item, String key) {
        for (Style s : styles(idx, item)) if (s.key().equals(key)) return s;
        return null;
    }

    /** Wendet einen Style auf ein Item an (null = Vanilla). */
    public static void apply(TextureIndex idx, Item item, Style style) {
        for (TextureEntry e : entries(idx, item)) Selection.chosen.remove(e.path);
        Selection.models.remove(item.id());
        if (style != null) {
            Selection.chosen.putAll(style.textures());
            if (style.has3d()) Selection.models.put(item.id(), style.name());
        }
        Selection.save();
    }
}
