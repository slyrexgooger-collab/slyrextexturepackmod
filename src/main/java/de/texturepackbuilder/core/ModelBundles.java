package de.texturepackbuilder.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.texturepackbuilder.core.Model.Bundle;
import de.texturepackbuilder.core.Model.SourcePack;
import de.texturepackbuilder.core.PvpCatalog.Item;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Findet für 3D-Packs alle Dateien, die zu einem PvP-Item gehören:
 * Item-Definition (assets/minecraft/items/x.json), Modelle (assets/minecraft/models/...),
 * deren Eltern-Modelle und alle darin benutzten Texturen – auch in eigenen Unterordnern.
 */
final class ModelBundles {
    private ModelBundles() {}

    /** Wandelt einen Modell-/Item-Pfad in die Form um, gegen die der Item-Regex prüft. */
    private static String matchKey(String path) {
        if (path.startsWith("assets/minecraft/items/")) {
            return "item/" + path.substring("assets/minecraft/items/".length(), path.length() - 5);
        }
        if (path.startsWith("assets/minecraft/models/")) {
            return path.substring("assets/minecraft/models/".length(), path.length() - 5);
        }
        return null;
    }

    static void collect(SourcePack pack, PackIO io, List<String> names, Set<String> nameSet, Map<String, Map<SourcePack, Bundle>> out) {
        Map<String, byte[]> cache = new HashMap<>();
        for (Item item : PvpCatalog.ITEMS) {
            Set<String> files = new TreeSet<>();
            Deque<String> queue = new ArrayDeque<>();
            for (String n : names) {
                if (!n.endsWith(".json")) continue;
                String key = matchKey(n);
                if (key != null && item.match().matcher(key).matches()) queue.add(n);
            }
            if (queue.isEmpty()) continue;

            while (!queue.isEmpty()) {
                String f = queue.poll();
                if (!files.add(f) || !f.endsWith(".json")) continue;
                byte[] data = read(pack, io, f, cache);
                if (data == null) continue;
                try {
                    walk(JsonParser.parseString(new String(data, StandardCharsets.UTF_8)), null, nameSet, queue);
                } catch (Exception ignored) {
                    // kaputtes JSON: Datei trotzdem mitnehmen
                }
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                for (String f : files) {
                    md.update(f.getBytes(StandardCharsets.UTF_8));
                    byte[] d = read(pack, io, f, cache);
                    if (d != null) md.update(d);
                }
                String hash = java.util.HexFormat.of().formatHex(md.digest()).substring(0, 16);
                out.computeIfAbsent(item.id(), k -> new HashMap<>()).put(pack, new Bundle(files, hash));
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] read(SourcePack pack, PackIO io, String f, Map<String, byte[]> cache) {
        if (cache.containsKey(f)) return cache.get(f);
        byte[] d;
        try {
            d = io.read(pack, f);
        } catch (Exception e) {
            d = null;
        }
        cache.put(f, d);
        return d;
    }

    /** Sucht Verweise auf Modelle ("model", "parent") und Texturen ("textures"-Objekt). */
    private static void walk(JsonElement el, String key, Set<String> nameSet, Deque<String> queue) {
        if (el == null) return;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getKey().equals("textures") && e.getValue().isJsonObject()) {
                    for (Map.Entry<String, JsonElement> t : e.getValue().getAsJsonObject().entrySet()) {
                        if (t.getValue().isJsonPrimitive()) addTexture(t.getValue().getAsString(), nameSet, queue);
                    }
                } else {
                    walk(e.getValue(), e.getKey(), nameSet, queue);
                }
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) walk(e, key, nameSet, queue);
        } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()
                && ("model".equals(key) || "parent".equals(key) || "base".equals(key))) {
            String[] id = split(el.getAsString());
            String f = "assets/" + id[0] + "/models/" + id[1] + ".json";
            if (nameSet.contains(f)) queue.add(f);
        }
    }

    private static void addTexture(String ref, Set<String> nameSet, Deque<String> queue) {
        if (ref.startsWith("#")) return;
        String[] id = split(ref);
        String f = "assets/" + id[0] + "/textures/" + id[1] + ".png";
        if (nameSet.contains(f)) {
            queue.add(f);
            if (nameSet.contains(f + ".mcmeta")) queue.add(f + ".mcmeta");
        }
    }

    private static String[] split(String id) {
        int c = id.indexOf(':');
        return c < 0 ? new String[]{"minecraft", id} : new String[]{id.substring(0, c), id.substring(c + 1)};
    }
}
