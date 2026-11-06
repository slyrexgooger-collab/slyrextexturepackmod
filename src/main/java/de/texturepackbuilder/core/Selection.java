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
 * Die Auswahl des Spielers: Texturpfad → Hash der gewählten Variante (fehlt ein Pfad, bleibt Vanilla),
 * dazu die Favoriten (Item|Pack).
 */
public final class Selection {
    public static final Map<String, String> chosen = new TreeMap<>();
    public static final Set<String> favorites = new TreeSet<>();
    /** PvP-Item → Name des Packs, dessen 3D-Modelle mitkopiert werden. */
    public static final Map<String, String> models = new TreeMap<>();
    /** Wurde seit dem letzten "Anwenden" etwas geändert? */
    public static boolean dirty = false;
    private static boolean loaded;

    private Selection() {}

    public static void load() {
        if (loaded) return;
        loaded = true;
        Path f = Dirs.selectionFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            JsonObject sel = obj.has("selection") ? obj.getAsJsonObject("selection") : obj; // altes Format
            for (Map.Entry<String, JsonElement> e : sel.entrySet()) {
                if (e.getValue().isJsonPrimitive()) chosen.put(e.getKey(), e.getValue().getAsString());
            }
            if (obj.has("models")) for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("models").entrySet())
                models.put(e.getKey(), e.getValue().getAsString());
            if (obj.has("favorites")) for (JsonElement e : obj.getAsJsonArray("favorites")) favorites.add(e.getAsString());
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht geladen werden", e);
        }
    }

    public static void save() {
        dirty = true;
        try {
            JsonObject sel = new JsonObject();
            chosen.forEach(sel::addProperty);
            JsonArray fav = new JsonArray();
            favorites.forEach(fav::add);
            JsonObject obj = new JsonObject();
            obj.add("selection", sel);
            obj.add("favorites", fav);
            JsonObject mod = new JsonObject();
            models.forEach(mod::addProperty);
            obj.add("models", mod);
            Path f = Dirs.selectionFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(obj));
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht gespeichert werden", e);
        }
    }

    public static boolean toggleFavorite(String key) {
        boolean wasDirty = dirty; // Favoriten ändern das Pack nicht
        boolean now = !favorites.remove(key);
        if (now) favorites.add(key);
        save();
        dirty = wasDirty;
        return now;
    }
}
