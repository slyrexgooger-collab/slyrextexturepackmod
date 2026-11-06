package de.texturepackbuilder.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.TexturePackBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Die Auswahl des Spielers – robust gegen Updates:
 * - items: PvP-Item → Pack-ID. Es wird das Pack gemerkt, nicht einzelne Bilder. Wird das Pack
 *   aktualisiert, gelten automatisch dessen neue Texturen und Modelle.
 * - textures: einzelne Textur → Pick (Pack-ID + Hash als Reserve), aus dem Tab "Einzeln".
 *   Pack "vanilla" erzwingt die Originaltextur.
 */
public final class Selection {
    public record Pick(String pack, String hash) {
        public static final String VANILLA = "vanilla";

        public boolean vanilla() {
            return VANILLA.equals(pack);
        }
    }

    private static final int VERSION = 3;

    public static final Map<String, String> items = new TreeMap<>();
    public static final Map<String, Pick> textures = new TreeMap<>();
    public static final Set<String> favorites = new TreeSet<>();
    /** Wurde seit dem letzten "Anwenden" etwas geändert? */
    public static boolean dirty = false;
    private static boolean loaded;

    private Selection() {}

    public static boolean isEmpty() {
        return items.isEmpty() && textures.isEmpty();
    }

    public static void clearAll() {
        items.clear();
        textures.clear();
        save();
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path f = Dirs.selectionFile();
            if (!Files.exists(f)) return;
            JsonObject obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            if (obj.has("version") && obj.get("version").getAsInt() >= VERSION) {
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("items").entrySet())
                    items.put(e.getKey(), e.getValue().getAsString());
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("textures").entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    textures.put(e.getKey(), new Pick(p.get("pack").getAsString(),
                            p.has("hash") ? p.get("hash").getAsString() : ""));
                }
            } else {
                // Altes Format: Pfad → Hash. Wird als Einzel-Auswahl übernommen (Pack unbekannt, nur Hash).
                JsonObject sel = obj.has("selection") ? obj.getAsJsonObject("selection") : obj;
                for (Map.Entry<String, JsonElement> e : sel.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) textures.put(e.getKey(), new Pick("", e.getValue().getAsString()));
                }
            }
            if (obj.has("favorites")) for (JsonElement e : obj.getAsJsonArray("favorites")) favorites.add(e.getAsString());
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht geladen werden", e);
        }
    }

    public static void save() {
        dirty = true;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("version", VERSION);
            JsonObject it = new JsonObject();
            items.forEach(it::addProperty);
            obj.add("items", it);
            JsonObject tex = new JsonObject();
            for (Map.Entry<String, Pick> e : textures.entrySet()) {
                JsonObject p = new JsonObject();
                p.addProperty("pack", e.getValue().pack());
                p.addProperty("hash", e.getValue().hash());
                tex.add(e.getKey(), p);
            }
            obj.add("textures", tex);
            JsonArray fav = new JsonArray();
            favorites.forEach(fav::add);
            obj.add("favorites", fav);
            Path f = Dirs.selectionFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(obj));
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht gespeichert werden", e);
        }
    }

    /** Favoriten ändern das Pack nicht, deshalb bleibt "dirty" unverändert. */
    public static void setFavorite(Iterable<String> keys, boolean on) {
        boolean wasDirty = dirty;
        for (String k : keys) {
            if (on) favorites.add(k);
            else favorites.remove(k);
        }
        save();
        dirty = wasDirty;
    }
}
