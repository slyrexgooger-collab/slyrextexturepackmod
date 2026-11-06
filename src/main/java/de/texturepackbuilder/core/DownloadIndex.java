package de.texturepackbuilder.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.TexturePackBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merkt sich zu jeder heruntergeladenen Datei, zu welchem Modrinth-Projekt sie gehört
 * (downloads/index.json). So bleibt ein Pack nach einem Update dasselbe Pack und die Auswahl bleibt erhalten.
 */
public final class DownloadIndex {
    public record Info(String projectId, String slug, String title, String versionId) {}

    private DownloadIndex() {}

    private static Path file() {
        return Dirs.downloads().resolve("index.json");
    }

    public static synchronized Map<String, Info> load() {
        Map<String, Info> out = new LinkedHashMap<>();
        try {
            Path f = file();
            if (!Files.exists(f)) return out;
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                out.put(e.getKey(), new Info(str(o, "projectId"), str(o, "slug"), str(o, "title"), str(o, "versionId")));
            }
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Download-Index konnte nicht gelesen werden", e);
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static void save(Map<String, Info> map) {
        try {
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Info> e : map.entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("projectId", e.getValue().projectId());
                o.addProperty("slug", e.getValue().slug());
                o.addProperty("title", e.getValue().title());
                o.addProperty("versionId", e.getValue().versionId());
                root.add(e.getKey(), o);
            }
            Files.createDirectories(file().getParent());
            Files.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Exception e) {
            TexturePackBuilder.LOG.warn("Download-Index konnte nicht gespeichert werden", e);
        }
    }

    /** Neue Datei eintragen; ältere Dateien desselben Projekts werden gelöscht (Update ersetzt die alte Version). */
    public static synchronized void put(String fileName, Info info) {
        Map<String, Info> map = load();
        List<String> old = new ArrayList<>();
        for (Map.Entry<String, Info> e : map.entrySet()) {
            if (!e.getKey().equals(fileName) && e.getValue().projectId().equals(info.projectId())) old.add(e.getKey());
        }
        for (String f : old) {
            map.remove(f);
            try {
                Files.deleteIfExists(Dirs.downloads().resolve(f));
            } catch (Exception ignored) {
            }
        }
        map.put(fileName, info);
        save(map);
    }

    public static synchronized void remove(String fileName) {
        Map<String, Info> map = load();
        if (map.remove(fileName) != null) save(map);
    }

    public static synchronized boolean hasProject(String projectId) {
        for (Map.Entry<String, Info> e : load().entrySet()) {
            if (e.getValue().projectId().equals(projectId) && Files.exists(Dirs.downloads().resolve(e.getKey()))) return true;
        }
        return false;
    }
}
