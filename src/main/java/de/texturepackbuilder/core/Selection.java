package de.texturepackbuilder.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.TexturePackBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Die Auswahl des Spielers: Texturpfad → Hash der gewählten Variante. Fehlt ein Pfad, bleibt Vanilla. */
public final class Selection {
    public static final Map<String, String> chosen = new TreeMap<>();
    private static boolean loaded;

    private Selection() {}

    public static void load() {
        if (loaded) return;
        loaded = true;
        Path f = Dirs.selectionFile();
        if (!Files.exists(f)) return;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) chosen.put(e.getKey(), e.getValue().getAsString());
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht geladen werden", e);
        }
    }

    public static void save() {
        try {
            JsonObject obj = new JsonObject();
            chosen.forEach(obj::addProperty);
            Path f = Dirs.selectionFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(obj));
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Auswahl konnte nicht gespeichert werden", e);
        }
    }
}
